package com.p2pcamera.mediaplayer

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.p2pcamera.mediaplayer.audio.PcmAudioPlayer
import com.p2pcamera.mediaplayer.video.H265Decoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * P2P Camera Media Player 主界面（横屏）
 *
 * 布局: 左侧设备管理面板（设备列表），右侧视频画面。
 *
 * 设备管理（SN 模式，运行时持久化到 App 内部存储 devices.json）:
 *   - 点击设备 → 选中(高亮), 不自动连接
 *   - 长按设备 → 菜单: 连接 / 断开 / 配置
 *   - 面板「删除」按钮 → 删除当前选中的设备(确认后真正删除)
 *   - 面板底部「添加设备」按钮 → 仅需输入设备 ID（SN）
 *   - 设备 ID 直接透传给 Rust 连接层；Rust 侧 viewer.rs 会自动判定其为
 *     完整 PeerId 还是短序列号(SN)，SN 经 relay 注册表解析出真实 PeerId 再连接。
 *   - 「配置」走 Rust 控制通道 (nativeSendControlCommand) 读取/下发摄像头编码/图像/系统参数
 *
 * viewer.toml 仅作为出厂默认种子（首次启动时若无已存设备则写入内部存储）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MediaPlayer"
        private const val POLL_INTERVAL_MS = 5L
        private const val VIEWER_TOML = "viewer.toml"
        private const val DEVICES_FILE = "devices.json"
    }

    /** 设备模型: peerId 为设备标识（16位序列号 SN 或完整 PeerId 均可，Rust 侧自动判定） */
    data class Device(val peerId: String)

    // ── viewer.toml 配置（relays / 默认设备等） ──
    data class ViewerTomlConfig(
        val relays: List<String>,
        val cameras: List<String>,
        val noAudio: Boolean,
        val enableMdns: Boolean,
        val streamType: String,
        /** 本地 serial→PeerId 静态映射；命中时无需 Relay 即可解析 SN 走 LAN 直连 */
        val serialMap: Map<String, String> = emptyMap(),
    )

    private var viewerConfig: ViewerTomlConfig? = null

    // ── 句柄 ──
    private var viewerHandle: Long = 0

    // ── 管道 ──
    private var decoder: H265Decoder? = null
    private var audioPlayer: PcmAudioPlayer? = null

    // ── 协程 ──
    private var pollJob: Job? = null

    // ── 状态 ──
    private var streamReady = false
    private var surfaceReady = false
    private var decoderConfigured = false

    // 当前正在连接/播放的设备 PeerId（null 表示未连接）
    private var currentDeviceId: String? = null

    // 设备列表中"选中"的设备 PeerId: 点击只选中(高亮), 不自动连接; 长按菜单/删除作用于它
    private var selectedDeviceId: String? = null

    // 配置弹窗: 等待连接建立后再拉取参数
    private var pendingConfigPeer: String? = null
    // 配置弹窗当前编辑的码流
    private var configStream = "main"

    // ── UI ──
    private lateinit var surfaceVideo: SurfaceView
    private lateinit var listDevices: ListView
    private lateinit var txtPlaceholder: TextView
    private lateinit var txtState: TextView
    private lateinit var txtStreamInfo: TextView
    private lateinit var btnReconnect: Button
    private lateinit var btnAddDevice: Button
    private var surface: android.view.Surface? = null

    // 设备列表数据源（持久化到内部存储）
    private val devices = mutableListOf<Device>()
    private lateinit var deviceAdapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceVideo = findViewById(R.id.surface_video)
        listDevices = findViewById(R.id.list_devices)
        txtPlaceholder = findViewById(R.id.txt_placeholder)
        txtState = findViewById(R.id.txt_state)
        txtStreamInfo = findViewById(R.id.txt_stream_info)
        btnReconnect = findViewById(R.id.btn_reconnect)
        btnAddDevice = findViewById(R.id.btn_add_device)

        // 设备列表适配器
        deviceAdapter = DeviceAdapter()
        listDevices.adapter = deviceAdapter
        // 点击 → 仅选中(高亮), 不自动连接, 与播放解耦
        listDevices.setOnItemClickListener { _, _, position, _ ->
            val dev = devices.getOrNull(position) ?: return@setOnItemClickListener
            selectedDeviceId = dev.peerId
            deviceAdapter.notifyDataSetChanged()
        }
        // 长按 → 弹出菜单: 连接 / 配置
        listDevices.setOnItemLongClickListener { _, _, position, _ ->
            val dev = devices.getOrNull(position) ?: return@setOnItemLongClickListener true
            showDeviceActionMenu(dev)
            true
        }

        btnAddDevice.setOnClickListener { showAddDeviceDialog() }
        // 删除设备: 针对列表"选中"的设备, 先确认再删除
        findViewById<Button>(R.id.btn_delete_device).setOnClickListener { onDeleteSelected() }

        // 加载 viewer.toml（出厂默认 + relays）
        loadViewerConfig()
        // 设备列表: 优先读内部存储，空则种入 viewer.toml 的默认设备
        loadDevices()

        // Surface 生命周期回调
        surfaceVideo.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "Surface created")
                surface = holder.surface
                surfaceReady = true
                tryConfigureDecoder()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                Log.i(TAG, "Surface changed: ${w}x${h}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "Surface destroyed")
                surfaceReady = false
                surface = null
                decoderConfigured = false
            }
        })

        // 重连按钮
        btnReconnect.setOnClickListener {
            currentDeviceId?.let { id -> connectToDevice(Device(id)) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        decoder?.release()
        audioPlayer?.release()
        if (viewerHandle != 0L) {
            RustBridge.nativeDestroy(viewerHandle)
            viewerHandle = 0
        }
    }

    // ═══════════════════════════════════════════════
    // 设备列表持久化（App 内部存储）
    // ═══════════════════════════════════════════════

    /** 设备存储: 读写 /data/data/.../files/devices.json */
    private object DeviceStore {
        fun load(ctx: MainActivity): List<Device> {
            val f = File(ctx.filesDir, DEVICES_FILE)
            if (!f.exists()) return emptyList()
            return try {
                val arr = JSONArray(f.readText())
                val list = mutableListOf<Device>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val peer = o.optString("peerId", "")
                    if (peer.isNotEmpty()) {
                        list.add(Device(peer))
                    }
                }
                list
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load $DEVICES_FILE: ${e.message}")
                emptyList()
            }
        }

        fun save(ctx: MainActivity, list: List<Device>) {
            try {
                val arr = JSONArray()
                for (d in list) {
                    val o = JSONObject()
                    o.put("peerId", d.peerId)
                    arr.put(o)
                }
                File(ctx.filesDir, DEVICES_FILE).writeText(arr.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save $DEVICES_FILE: ${e.message}", e)
            }
        }
    }

    /** 加载设备列表: 内部存储优先, 空则种入 viewer.toml 默认设备 */
    private fun loadDevices() {
        var loaded = DeviceStore.load(this)
        if (loaded.isEmpty()) {
            val seed = viewerConfig?.cameras ?: emptyList()
            if (seed.isNotEmpty()) {
                loaded = seed.map { Device(it) }
                DeviceStore.save(this, loaded)
                Log.i(TAG, "Seeded ${loaded.size} devices from $VIEWER_TOML")
            }
        }
        devices.clear()
        devices.addAll(loaded)
        selectedDeviceId = devices.firstOrNull()?.peerId
        deviceAdapter.notifyDataSetChanged()
    }

    // ═══════════════════════════════════════════════
    // 设备菜单 / 增删 / 重命名
    // ═══════════════════════════════════════════════

    private fun showAddDeviceDialog() {
        val etId = EditText(this).apply {
            hint = getString(R.string.hint_peer_id)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(etId)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_add_title)
            .setView(layout)
            .setPositiveButton(R.string.btn_add) { _, _ ->
                val id = etId.text.toString().trim()
                if (id.isEmpty()) { toast(R.string.toast_empty_peer); return@setPositiveButton }
                if (devices.any { it.peerId == id }) { toast(R.string.toast_dup_peer); return@setPositiveButton }
                devices.add(Device(id))
                DeviceStore.save(this, devices)
                deviceAdapter.notifyDataSetChanged()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** 面板"删除"按钮: 删除列表"选中"的设备, 未选中则提示 */
    private fun onDeleteSelected() {
        val peer = selectedDeviceId
        if (peer == null) {
            toast(R.string.toast_no_select)
            return
        }
        val dev = devices.firstOrNull { it.peerId == peer } ?: run {
            toast(R.string.toast_no_select)
            return
        }
        showDeleteConfirm(dev)
    }

    /** 删除确认框: 确认后删除指定设备 */
    private fun showDeleteConfirm(dev: Device) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dlg_delete_title)
            .setMessage(getString(R.string.dlg_delete_msg, shortId(dev.peerId)))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                if (currentDeviceId == dev.peerId) stopCurrent()
                if (selectedDeviceId == dev.peerId) selectedDeviceId = null
                devices.remove(dev)
                DeviceStore.save(this, devices)
                deviceAdapter.notifyDataSetChanged()
                toast(getString(R.string.toast_deleted, shortId(dev.peerId)))
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** 长按菜单: 连接 / 断开 / 配置（不显示设备 ID） */
    private fun showDeviceActionMenu(dev: Device) {
        val items = arrayOf(
            getString(R.string.menu_connect),
            getString(R.string.menu_disconnect),
            getString(R.string.menu_config),
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> onDeviceActivate(dev)   // 连接
                    1 -> disconnectDevice(dev)   // 断开
                    2 -> openConfig(dev)          // 配置
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** 长按菜单"断开": 仅当该设备为当前播放设备时断开连接 */
    private fun disconnectDevice(dev: Device) {
        if (dev.peerId == currentDeviceId) {
            stopCurrent()
            toast(getString(R.string.toast_disconnected, shortId(dev.peerId)))
        } else {
            toast(R.string.toast_not_connected)
        }
    }

    // ═══════════════════════════════════════════════
    // 连接管理
    // ═══════════════════════════════════════════════

    /**
     * 设备点击/长按"播放"的统一入口：
     * - 当前正在播放该设备 → 再次激活即断开（stopCurrent）
     * - 其他设备 / 未连接 → 连接（切设备时 connectToDevice 内部会先销毁旧连接）
     */
    private fun onDeviceActivate(dev: Device) {
        if (dev.peerId == currentDeviceId && viewerHandle != 0L) {
            stopCurrent()
        } else {
            connectToDevice(dev)
        }
    }

    /** 点击设备 → 连接播放（切换设备时先销毁旧连接，避免多个设备同时连接） */
    private fun connectToDevice(dev: Device) {
        val peer = dev.peerId
        val config = viewerConfig
        val relays = config?.relays ?: emptyList()
        if (relays.isEmpty()) {
            updateState("缺少 relay 配置")
            return
        }
        currentDeviceId = peer
        deviceAdapter.notifyDataSetChanged()
        startConnection(
            relays = relays,
            deviceId = peer,
            enableMdns = config?.enableMdns ?: false,
            streamType = config?.streamType ?: "auto",
            noAudio = config?.noAudio ?: false,
            serialMap = config?.serialMap ?: emptyMap(),
        )
    }

    /** 停止当前播放并释放句柄（用于删除当前设备） */
    private fun stopCurrent() {
        stopPolling()
        decoder?.release()
        decoder = null
        audioPlayer?.release()
        audioPlayer = null
        if (viewerHandle != 0L) {
            RustBridge.nativeDestroy(viewerHandle)
            viewerHandle = 0
        }
        streamReady = false
        decoderConfigured = false
        currentDeviceId = null
        txtPlaceholder.visibility = View.VISIBLE
        btnReconnect.visibility = View.GONE
        updateState(getString(R.string.state_idle))
        deviceAdapter.notifyDataSetChanged()
    }

    private fun startConnection(
        relays: List<String>,
        deviceId: String,
        enableMdns: Boolean = false,
        streamType: String = "auto",
        noAudio: Boolean = false,
        serialMap: Map<String, String> = emptyMap(),
    ) {
        Log.i(TAG, "Starting connection: relays=$relays deviceId=$deviceId enableMdns=$enableMdns streamType=$streamType noAudio=$noAudio serialMap=$serialMap")

        // 销毁旧实例
        stopPolling()
        decoder?.release()
        decoder = null
        audioPlayer?.release()
        audioPlayer = null
        if (viewerHandle != 0L) {
            RustBridge.nativeDestroy(viewerHandle)
        }

        // 创建新 viewer
        viewerHandle = RustBridge.nativeCreate()
        Log.i(TAG, "Created viewer handle: $viewerHandle")

        // 发送连接命令
        val relaysArray = JSONArray().apply {
            for (r in relays) put(r)
        }
        val config = JSONObject().apply {
            put("relays", relaysArray)
            put("deviceId", deviceId)
            put("enable_mdns", enableMdns)
            put("stream_type", streamType)
            put("no_audio", noAudio)
            // 本地 serial→PeerId 静态映射：命中时 Rust 侧无需连 Relay 即可解析 SN，
            // 并能在 LAN 内 mDNS 直接匹配目标走直连（避免等待中继注册表解析）。
            if (serialMap.isNotEmpty()) {
                val serialMapObj = JSONObject()
                for ((sn, pid) in serialMap) serialMapObj.put(sn, pid)
                put("serial_map", serialMapObj)
            }
        }
        val ok = RustBridge.nativeConnect(viewerHandle, config.toString())
        if (!ok) {
            updateState("连接失败")
            return
        }

        streamReady = false
        decoderConfigured = false
        updateState("连接中...")
        txtPlaceholder.visibility = View.GONE
        btnReconnect.visibility = View.GONE

        // 启动轮询
        startPolling()
    }

    // ═══════════════════════════════════════════════
    // 设备配置（控制通道: 读取/下发编码/图像/系统参数）
    // ═══════════════════════════════════════════════

    /** 打开配置: 已连接则直接拉取; 未连接则先连接, StreamReady 后自动拉取 */
    private fun openConfig(dev: Device) {
        val peer = dev.peerId
        val isCurrent = currentDeviceId == peer && streamReady
        if (isCurrent) {
            showConfigDialog(peer)
        } else {
            pendingConfigPeer = peer
            connectToDevice(dev)
        }
    }

    /** 显示设备配置弹窗, 走控制通道 Get/Set */
    private fun showConfigDialog(peer: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_device_config, null)
        val spinStream = view.findViewById<Spinner>(R.id.spin_stream)
        val spinCodec = view.findViewById<Spinner>(R.id.spin_codec)
        val spinRcMode = view.findViewById<Spinner>(R.id.spin_rc_mode)
        val spinResolution = view.findViewById<Spinner>(R.id.spin_resolution)
        val editFps = view.findViewById<EditText>(R.id.edit_fps)
        val editBitrate = view.findViewById<EditText>(R.id.edit_bitrate)
        val editGop = view.findViewById<EditText>(R.id.edit_gop)
        val editBright = view.findViewById<EditText>(R.id.edit_brightness)
        val editContrast = view.findViewById<EditText>(R.id.edit_contrast)
        val editSat = view.findViewById<EditText>(R.id.edit_saturation)
        val editSharp = view.findViewById<EditText>(R.id.edit_sharpness)
        val editName = view.findViewById<EditText>(R.id.edit_name)
        val camId = 0

        // 分段 tab: 编码 / 图像 / 系统（与 viewer 配置窗体分区一致）
        val tabEncode = view.findViewById<Button>(R.id.tab_encode)
        val tabImage = view.findViewById<Button>(R.id.tab_image)
        val tabSystem = view.findViewById<Button>(R.id.tab_system)
        val llEncode = view.findViewById<LinearLayout>(R.id.ll_encode)
        val llImage = view.findViewById<LinearLayout>(R.id.ll_image)
        val llSystem = view.findViewById<LinearLayout>(R.id.ll_system)
        val tabSel = R.drawable.bg_tab_selected
        val tabUnsel = R.drawable.bg_tab_unselected
        fun selectConfigTab(which: Int) {
            llEncode.visibility = if (which == 0) View.VISIBLE else View.GONE
            llImage.visibility = if (which == 1) View.VISIBLE else View.GONE
            llSystem.visibility = if (which == 2) View.VISIBLE else View.GONE
            tabEncode.setBackgroundResource(if (which == 0) tabSel else tabUnsel)
            tabImage.setBackgroundResource(if (which == 1) tabSel else tabUnsel)
            tabSystem.setBackgroundResource(if (which == 2) tabSel else tabUnsel)
            tabEncode.setTextColor(if (which == 0) Color.WHITE else Color.GRAY)
            tabImage.setTextColor(if (which == 1) Color.WHITE else Color.GRAY)
            tabSystem.setTextColor(if (which == 2) Color.WHITE else Color.GRAY)
        }
        tabEncode.setOnClickListener { selectConfigTab(0) }
        tabImage.setOnClickListener { selectConfigTab(1) }
        tabSystem.setOnClickListener { selectConfigTab(2) }
        selectConfigTab(0)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_apply, null)
            .create()

        // 当前拉取的编码配置（作为下发基底，保留未编辑字段）
        var encoderBase: JSONObject? = null

        fun fetchEncoder(stream: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val resp = sendControl(
                    JSONObject().apply { put("type", "GetEncoderConfig"); put("stream", stream) }.toString()
                )
                runOnUiThread {
                    if (resp == null || !resp.optBoolean("ok", false)) {
                        val err = resp?.optString("error") ?: "no response"
                        toast(getString(R.string.config_fetch_fail, err))
                        return@runOnUiThread
                    }
                    val ec = resp.optJSONObject("encoder_config") ?: return@runOnUiThread
                    encoderBase = ec
                    spinCodec.setSelection(if (ec.optString("output_data_type", "H.265") == "H.264") 1 else 0)
                    spinRcMode.setSelection(if (ec.optString("rc_mode", "CBR") == "VBR") 1 else 0)
                    val w = ec.optInt("width", 0); val h = ec.optInt("height", 0)
                    spinResolution.setSelection(
                        when {
                            w == 1920 && h == 1080 -> 0
                            w == 1280 && h == 720 -> 1
                            w == 960 && h == 540 -> 2
                            w == 640 && h == 360 -> 3
                            else -> 1
                        }
                    )
                    editFps.setText(ec.optInt("dst_frame_rate_num", 25).toString())
                    editBitrate.setText(ec.optInt("max_rate", 2000).toString())
                    editGop.setText(ec.optInt("gop", 50).toString())
                }
            }
        }

        fun fetchImageAndSystem() {
            lifecycleScope.launch(Dispatchers.IO) {
                val img = sendControl(
                    JSONObject().apply { put("type", "GetImageConfig"); put("cam_id", camId) }.toString()
                )
                val sys = sendControl(JSONObject().apply { put("type", "GetSystemConfig") }.toString())
                runOnUiThread {
                    img?.optJSONObject("image_config")?.let { ic ->
                        ic.optJSONObject("adjustment")?.let { a ->
                            editBright.setText(a.optInt("brightness", 0).toString())
                            editContrast.setText(a.optInt("contrast", 0).toString())
                            editSat.setText(a.optInt("saturation", 0).toString())
                            editSharp.setText(a.optInt("sharpness", 0).toString())
                        }
                    }
                    sys?.optJSONObject("system_config")?.optString("device_name")?.let { editName.setText(it) }
                }
            }
        }

        fun applyConfig() {
            if (viewerHandle == 0L) { toast(R.string.config_not_connected); return }
            lifecycleScope.launch(Dispatchers.IO) {
                var okAll = true
                // 1) 编码参数
                val ec = encoderBase
                if (ec != null) {
                    val cfg = JSONObject(ec.toString())
                    cfg.put("output_data_type", if (spinCodec.selectedItemPosition == 1) "H.264" else "H.265")
                    cfg.put("rc_mode", if (spinRcMode.selectedItemPosition == 1) "VBR" else "CBR")
                    val (rw, rh) = when (spinResolution.selectedItemPosition) {
                        0 -> 1920 to 1080
                        1 -> 1280 to 720
                        2 -> 960 to 540
                        3 -> 640 to 360
                        else -> 1280 to 720
                    }
                    cfg.put("width", rw)
                    cfg.put("height", rh)
                    cfg.put("dst_frame_rate_num", editFps.text.toString().toIntOrNull() ?: cfg.optInt("dst_frame_rate_num", 25))
                    cfg.put("dst_frame_rate_den", 1)
                    cfg.put("max_rate", editBitrate.text.toString().toIntOrNull() ?: cfg.optInt("max_rate", 2000))
                    cfg.put("gop", editGop.text.toString().toIntOrNull() ?: cfg.optInt("gop", 50))
                    val req = JSONObject().apply { put("type", "SetEncoderConfig"); put("stream", configStream); put("config", cfg) }
                    val resp = sendControl(req.toString())
                    if (resp == null || !resp.optBoolean("ok", false)) okAll = false
                } else {
                    okAll = false
                }
                // 2) 图像参数
                val bright = editBright.text.toString().toIntOrNull()
                val contrast = editContrast.text.toString().toIntOrNull()
                val sat = editSat.text.toString().toIntOrNull()
                val sharp = editSharp.text.toString().toIntOrNull()
                val imgReq = JSONObject().apply {
                    put("type", "SetImageConfig"); put("cam_id", camId)
                    put("adjustment", JSONObject().apply {
                        if (bright != null) put("brightness", bright.coerceIn(0, 100))
                        if (contrast != null) put("contrast", contrast.coerceIn(0, 100))
                        if (sat != null) put("saturation", sat.coerceIn(0, 100))
                        if (sharp != null) put("sharpness", sharp.coerceIn(0, 100))
                    })
                }
                val imgResp = sendControl(imgReq.toString())
                if (imgResp == null || !imgResp.optBoolean("ok", false)) okAll = false
                // 3) 系统参数（设备名）
                val name = editName.text.toString().trim()
                if (name.isNotEmpty()) {
                    val sysReq = JSONObject().apply { put("type", "SetSystemConfig"); put("device_name", name) }
                    val sysResp = sendControl(sysReq.toString())
                    if (sysResp == null || !sysResp.optBoolean("ok", false)) okAll = false
                }
                runOnUiThread {
                    if (okAll) toast(R.string.config_apply_ok)
                    else toast(getString(R.string.config_apply_fail, "部分失败"))
                    dialog.dismiss()
                }
            }
        }

        spinStream.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                configStream = when (pos) { 0 -> "main"; 1 -> "sub"; else -> "third" }
                fetchEncoder(configStream)
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { applyConfig() }
        }

        dialog.show()
        fetchEncoder(configStream)
        fetchImageAndSystem()
    }

    /** 发送控制命令（后台线程调用） */
    private fun sendControl(reqJson: String): JSONObject? {
        if (viewerHandle == 0L) return null
        return try {
            val resp = RustBridge.nativeSendControlCommand(viewerHandle, reqJson)
            if (resp.isNullOrEmpty()) null else JSONObject(resp)
        } catch (e: Exception) {
            Log.e(TAG, "sendControl failed: $reqJson", e)
            null
        }
    }

    // ═══════════════════════════════════════════════
    // viewer.toml 配置加载（仅 relays / 默认设备种子）
    // ═══════════════════════════════════════════════

    private fun loadViewerConfig() {
        try {
            val toml = assets.open(VIEWER_TOML).bufferedReader().use { it.readText() }
            val config = parseViewerToml(toml)
            viewerConfig = config
            Log.i(TAG, "Loaded $VIEWER_TOML: relays=${config.relays.size} cameras=${config.cameras.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $VIEWER_TOML from assets: ${e.message}")
            viewerConfig = null
        }
    }

    /**
     * 简易 TOML 解析器（仅支持 viewer.toml 所需格式）
     * 支持顶层字段与 [serial_map] 子表（SN = "PeerId" 形式）。
     */
    private fun parseViewerToml(toml: String): ViewerTomlConfig {
        var relays = listOf<String>()
        val relayList = mutableListOf<String>() // 来自 [[relay_list]] 结构化格式
        var cameraSerials = listOf<String>()
        var noAudio = false
        var enableMdns = false
        var streamType = "auto"
        val serialMap = mutableMapOf<String, String>()

        val lines = toml.replace(Regex("\\r\\n?"), "\n").lines()

        // [[relay_list]] 表内累积字段（与 Rust RelayConfig::to_multiaddr 对齐）
        var rlIp = ""
        var rlPort = 4001
        var rlTransport = "quic"
        var rlPeer = ""
        fun flushRelay() {
            if (rlIp.isNotEmpty() && rlPeer.isNotEmpty()) {
                buildRelayMultiaddr(rlIp, rlPort, rlTransport, rlPeer)?.let { relayList.add(it) }
            }
            rlIp = ""; rlPort = 4001; rlTransport = "quic"; rlPeer = ""
        }

        var inTable: String? = null // null=顶层, "relay_list", "serial_map"
        val topLevel = StringBuilder()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                // 离开上一个表
                if (inTable == "relay_list") flushRelay()
                inTable = when {
                    line.startsWith("[[") && line.equals("[[relay_list]]", ignoreCase = true) -> "relay_list"
                    line.equals("[serial_map]", ignoreCase = true) -> "serial_map"
                    else -> null
                }
                if (inTable == null) topLevel.append(line).append(" ")
                continue
            }
            when (inTable) {
                "relay_list" -> {
                    val m = Regex("""(\w+)\s*=\s*"?([^"\n#]+?)"?\s*$""").find(line)
                    if (m != null) {
                        when (m.groupValues[1].trim().lowercase()) {
                            "ip" -> rlIp = m.groupValues[2].trim().trim('"')
                            "port" -> rlPort = m.groupValues[2].trim().toIntOrNull() ?: 4001
                            "transport" -> rlTransport = m.groupValues[2].trim().trim('"')
                            "peer_id" -> rlPeer = m.groupValues[2].trim().trim('"')
                        }
                    }
                }
                "serial_map" -> {
                    val m = Regex("""(\S+)\s*=\s*"?([^"\n]+?)"?\s*$""").find(line)
                    if (m != null) {
                        val k = m.groupValues[1].trim()
                        val v = m.groupValues[2].trim().trim('"')
                        if (k.isNotEmpty() && v.isNotEmpty()) serialMap[k] = v
                    }
                }
                else -> topLevel.append(line).append(" ")
            }
        }
        // 文件末尾 flush 最后一个 relay_list
        if (inTable == "relay_list") flushRelay()

        // 顶层字段解析（兼容旧 relays / camera_serials 等）
        val pattern = Regex("""(\w+)\s*=\s*(.+?)(?=\s+\w+\s*=|$)""")
        for (match in pattern.findAll(topLevel.toString())) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()
            when (key) {
                "relays" -> relays = parseStringArray(value)
                "camera_serials", "cameras" -> cameraSerials = parseStringArray(value)
                "no_audio" -> noAudio = value.toBooleanStrictOrNull() ?: false
                "enable_mdns" -> enableMdns = value.toBooleanStrictOrNull() ?: false
                "stream_type", "stream" -> {
                    val s = value.trim('"')
                    if (s.isNotEmpty()) streamType = s
                }
            }
        }

        // 优先用结构化 [[relay_list]]，无则退回旧 relays 字符串数组
        val finalRelays = if (relayList.isNotEmpty()) relayList.toList() else relays
        val cameras = LinkedHashSet<String>()
        cameras.addAll(cameraSerials)

        return ViewerTomlConfig(
            relays = finalRelays,
            cameras = cameras.toList(),
            noAudio = noAudio,
            enableMdns = enableMdns,
            streamType = streamType,
            serialMap = serialMap,
        )
    }

    /** 将结构化 relay 字段组装成 libp2p multiaddr（与 Rust RelayConfig::to_multiaddr 一致） */
    private fun buildRelayMultiaddr(ip: String, port: Int, transport: String, peerId: String): String? {
        val t = ip.trim().trim { it == '[' || it == ']' }
        if (t.isEmpty() || peerId.trim().isEmpty()) return null
        val family = if (t.contains(':')) "ip6" else "ip4"
        return when (transport.lowercase()) {
            "tcp" -> "/$family/$t/tcp/$port/p2p/$peerId"
            "quic" -> "/$family/$t/udp/$port/quic-v1/p2p/$peerId"
            else -> null
        }
    }

    private fun parseStringArray(value: String): List<String> {
        val arrayMatch = Regex("""\[(.*)]""").find(value) ?: return emptyList()
        return arrayMatch.groupValues[1]
            .split(",")
            .map { it.trim().trim('"').trim() }
            .filter { it.isNotEmpty() }
    }

    // ═══════════════════════════════════════════════
    // 轮询循环
    // ═══════════════════════════════════════════════

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.Default) {
            Log.i(TAG, "Poll loop started")
            var eventSequence = 0

            while (isActive && viewerHandle != 0L) {
                val eventJson = RustBridge.nativePollEvent(viewerHandle)
                if (eventJson != null) {
                    eventSequence++
                    handleEvent(eventJson, eventSequence)
                }

                if (streamReady && decoderConfigured) {
                    val raw = RustBridge.nativePollVideoFrame(viewerHandle)
                    if (raw != null && raw.size > 9) {
                        val ptsUs = extractPtsUs(raw)
                        val flags = extractVideoFlags(raw)
                        val nalData = extractVideoFrameData(raw)
                        val isKeyframe = (flags and 0x04) != 0
                        decoder?.feedFrame(nalData, ptsUs, isKeyframe)
                    }
                }

                if (streamReady && audioPlayer != null) {
                    val raw = RustBridge.nativePollAudioFrame(viewerHandle)
                    if (raw != null && raw.size > 8) {
                        val pcmData = extractFrameData(raw)
                        audioPlayer?.write(pcmData)
                    }
                }

                delay(POLL_INTERVAL_MS)
            }
            Log.i(TAG, "Poll loop ended")
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ═══════════════════════════════════════════════
    // 事件处理
    // ═══════════════════════════════════════════════

    private fun handleEvent(json: String, seq: Int) {
        try {
            val event = JSONObject(json)
            val type = event.optString("type", "unknown")
            Log.i(TAG, "Event #$seq: $type")

            runOnUiThread {
                when (type) {
                    "Connecting" -> updateState("连接中...")
                    "Connected" -> {
                        val connType = event.optString("connection_type", "relay")
                        updateState(if (connType == "relay") "已连接 (Relay)" else "直连 ($connType)")
                    }
                    "DirectUpgraded" -> {
                        val connType = event.optString("connection_type", "DCUtR")
                        updateState("直连 ($connType)")
                    }
                    "StreamReady" -> {
                        streamReady = true
                        updateState("码流就绪")
                        txtPlaceholder.visibility = View.GONE
                        deviceAdapter.notifyDataSetChanged()
                        tryConfigureDecoder()
                        audioPlayer = PcmAudioPlayer().also { it.play() }
                        // 若在等待配置, 连接就绪后自动弹出配置弹窗
                        pendingConfigPeer?.let { peer ->
                            pendingConfigPeer = null
                            showConfigDialog(peer)
                        }
                    }
                    "Disconnected" -> {
                        streamReady = false
                        decoderConfigured = false
                        updateState("已断开")
                        btnReconnect.visibility = View.VISIBLE
                        deviceAdapter.notifyDataSetChanged()
                    }
                    "Error" -> {
                        val msg = event.optString("message", "未知错误")
                        updateState("错误: $msg")
                        Log.e(TAG, "Error: $msg")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse event: $json", e)
        }
    }

    // ═══════════════════════════════════════════════
    // 解码器配置
    // ═══════════════════════════════════════════════

    private fun tryConfigureDecoder() {
        if (!streamReady || !surfaceReady || decoderConfigured) return

        val s = surface ?: return
        try {
            decoder = H265Decoder().also {
                it.configure(s)
                it.start()
            }
            decoderConfigured = true
            Log.i(TAG, "Decoder configured & started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure decoder: ${e.message}", e)
            runOnUiThread { updateState("解码器初始化失败") }
        }
    }

    // ═══════════════════════════════════════════════
    // UI 辅助
    // ═══════════════════════════════════════════════

    private fun updateState(state: String) {
        txtState.text = state
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 缩短 PeerId 显示：前 12 位…后 8 位 */
    private fun shortId(id: String): String =
        if (id.length > 24) "${id.take(12)}…${id.takeLast(8)}" else id

    // ═══════════════════════════════════════════════
    // 设备列表适配器
    // ═══════════════════════════════════════════════

    private inner class DeviceAdapter : BaseAdapter() {
        override fun getCount(): Int = devices.size
        override fun getItem(position: Int): Any = devices[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_device, parent, false)

            val dev = devices[position]
            val name = view.findViewById<TextView>(R.id.txt_device_name)
            val status = view.findViewById<TextView>(R.id.txt_device_status)

            name.text = shortId(dev.peerId)

            val isCurrent = dev.peerId == currentDeviceId
            val isSelected = dev.peerId == selectedDeviceId
            when {
                isCurrent && streamReady -> {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.device_status_connected)
                    view.setBackgroundColor(Color.parseColor("#33FFFFFF"))
                }
                isCurrent -> {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.state_connecting)
                    view.setBackgroundColor(Color.parseColor("#22FFFFFF"))
                }
                isSelected -> {
                    status.visibility = View.GONE
                    view.setBackgroundColor(Color.parseColor("#264F78"))
                }
                else -> {
                    status.visibility = View.GONE
                    view.setBackgroundColor(Color.TRANSPARENT)
                }
            }
            return view
        }
    }
}
