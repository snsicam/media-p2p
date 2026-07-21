package com.p2pcamera.mediaplayer

import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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

/**
 * P2P Camera Media Player 主界面
 *
 * 生命周期:
 *   1. onCreate → 初始化 UI
 *   2. 用户输入 relay + deviceId → 点击连接
 *   3. nativeCreate + nativeConnect → 等 StreamReady 事件
 *   4. 启动 H265Decoder + PcmAudioPlayer
 *   5. 主轮询循环: video → decoder, audio → player, events → UI
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MediaPlayer"
        private const val POLL_INTERVAL_MS = 5L
        private const val VIEWER_TOML = "viewer.toml"
    }

    // ── viewer.toml 配置 ──
    data class ViewerTomlConfig(
        val relays: List<String>,
        val camera: String,
        val noAudio: Boolean,
        val enableMdns: Boolean,
        val streamType: String,
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

    // ── UI ──
    private lateinit var surfaceVideo: SurfaceView
    private lateinit var panelConnect: View
    private lateinit var inputRelay: EditText
    private lateinit var inputDeviceId: EditText
    private lateinit var btnConnect: Button
    private lateinit var txtState: TextView
    private lateinit var txtStreamInfo: TextView
    private lateinit var btnReconnect: Button
    private var surface: android.view.Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceVideo = findViewById(R.id.surface_video)
        panelConnect = findViewById(R.id.panel_connect)
        inputRelay = findViewById(R.id.input_relay)
        inputDeviceId = findViewById(R.id.input_device_id)
        btnConnect = findViewById(R.id.btn_connect)
        txtState = findViewById(R.id.txt_state)
        txtStreamInfo = findViewById(R.id.txt_stream_info)
        btnReconnect = findViewById(R.id.btn_reconnect)

        // 加载 viewer.toml 配置
        loadViewerConfig()

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

        // 连接按钮
        btnConnect.setOnClickListener {
            // 优先使用 viewer.toml 配置，否则使用 UI 输入
            val config = viewerConfig
            if (config != null) {
                startConnection(config.relays, config.camera, config.enableMdns, config.streamType, config.noAudio)
            } else {
                val relay = inputRelay.text.toString().trim()
                val deviceId = inputDeviceId.text.toString().trim()
                if (relay.isNotEmpty() && deviceId.isNotEmpty()) {
                    startConnection(listOf(relay), deviceId)
                }
            }
        }

        // 重连按钮
        btnReconnect.setOnClickListener { reconnect() }
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
    // 连接管理
    // ═══════════════════════════════════════════════

    private fun startConnection(
        relays: List<String>,
        deviceId: String,
        enableMdns: Boolean = false,
        streamType: String = "auto",
        noAudio: Boolean = false,
    ) {
        Log.i(TAG, "Starting connection: relays=$relays deviceId=$deviceId enableMdns=$enableMdns streamType=$streamType noAudio=$noAudio")

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
        }
        val ok = RustBridge.nativeConnect(viewerHandle, config.toString())
        if (!ok) {
            updateState("连接失败")
            return
        }

        streamReady = false
        decoderConfigured = false
        updateState("连接中...")
        hideConnectPanel()
        btnReconnect.visibility = View.GONE

        // 启动轮询
        startPolling()
    }

    private fun reconnect() {
        panelConnect.visibility = View.VISIBLE
        btnReconnect.visibility = View.GONE
        updateState("未连接")
        txtStreamInfo.text = ""
    }

    // ═══════════════════════════════════════════════
    // viewer.toml 配置加载
    // ═══════════════════════════════════════════════

    /**
     * 从 assets/viewer.toml 加载配置
     *
     * TOML 格式:
     *   relays = ["/ip4/.../udp/.../quic-v1/p2p/12D3...", ...]
     *   camera = "12D3KooW..."
     *   no_audio = false
     *   enable_mdns = false
     *   stream_type = "auto"
     */
    private fun loadViewerConfig() {
        try {
            val toml = assets.open(VIEWER_TOML).bufferedReader().use { it.readText() }
            val config = parseViewerToml(toml)
            viewerConfig = config
            Log.i(TAG, "Loaded $VIEWER_TOML: relays=${config.relays.size} camera=${config.camera} " +
                    "noAudio=${config.noAudio} enableMdns=${config.enableMdns} streamType=${config.streamType}")

            // 用配置值预填充 UI 输入框
            if (config.relays.isNotEmpty()) {
                inputRelay.setText(config.relays.joinToString(", "))
            }
            if (config.camera.isNotEmpty()) {
                inputDeviceId.setText(config.camera)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $VIEWER_TOML from assets: ${e.message}")
            viewerConfig = null
        }
    }

    /**
     * 简易 TOML 解析器（仅支持 viewer.toml 所需的格式）
     *
     * 支持的格式:
     *   key = "value"           -> 字符串
     *   key = true/false        -> 布尔
     *   key = ["a", "b", ...]   -> 字符串数组（可跨行）
     */
    private fun parseViewerToml(toml: String): ViewerTomlConfig {
        var relays = listOf<String>()
        var camera = ""
        var noAudio = false
        var enableMdns = false
        var streamType = "auto"

        // 将多行数组合并成单行（TOML 数组可跨行书写）
        val normalized = toml.replace(Regex("\\r\\n?"), "\n")
            .replace(Regex("\\n\\s*"), " ")

        // 按顶层 key = value 解析
        val pattern = Regex("""(\w+)\s*=\s*(.+?)(?=\s+\w+\s*=|$)""")
        for (match in pattern.findAll(normalized)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()

            when (key) {
                "relays" -> {
                    // 解析 ["addr1", "addr2", ...]
                    val arrayMatch = Regex("""\[(.*)]""").find(value)
                    if (arrayMatch != null) {
                        relays = arrayMatch.groupValues[1]
                            .split(",")
                            .map { it.trim().trim('"').trim() }
                            .filter { it.isNotEmpty() }
                    }
                }
                "camera" -> {
                    camera = value.trim('"')
                }
                "no_audio" -> {
                    noAudio = value.toBooleanStrictOrNull() ?: false
                }
                "enable_mdns" -> {
                    enableMdns = value.toBooleanStrictOrNull() ?: false
                }
                "stream_type", "stream" -> {
                    val s = value.trim('"')
                    if (s.isNotEmpty()) streamType = s
                }
            }
        }

        return ViewerTomlConfig(
            relays = relays,
            camera = camera,
            noAudio = noAudio,
            enableMdns = enableMdns,
            streamType = streamType,
        )
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
                // ── 事件 ──
                val eventJson = RustBridge.nativePollEvent(viewerHandle)
                if (eventJson != null) {
                    eventSequence++
                    handleEvent(eventJson, eventSequence)
                }

                // ── 视频帧 ──
                // Rust 视频帧格式: [PTS 8B] + [flags 1B] + [H.265 NAL data]
                // 关键帧用 Rust 转发的 flags bit 2 (0x04) 判定，权威且无需重复字节扫描
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

                // ── 音频帧 ──
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
                    "Connecting" -> {
                        updateState("连接中...")
                    }
                    "Connected" -> {
                        val connType = event.optString("connection_type", "relay")
                        Log.i(TAG, "Connection type: $connType")
                        updateState(if (connType == "relay") "已连接 (Relay)" else "直连 ($connType)")
                    }
                    "DirectUpgraded" -> {
                        val connType = event.optString("connection_type", "DCUtR")
                        Log.i(TAG, "Direct upgraded: $connType (stream sub → main)")
                        updateState("直连 ($connType)")
                    }
                    "StreamReady" -> {
                        streamReady = true
                        updateState("码流就绪")
                        tryConfigureDecoder()
                        // 启动音频播放
                        audioPlayer = PcmAudioPlayer().also { it.play() }
                    }
                    "Disconnected" -> {
                        streamReady = false
                        decoderConfigured = false
                        updateState("已断开")
                        btnReconnect.visibility = View.VISIBLE
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

    private fun hideConnectPanel() {
        panelConnect.visibility = View.GONE
    }
}
