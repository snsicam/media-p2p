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
    }

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
            val relay = inputRelay.text.toString().trim()
            val deviceId = inputDeviceId.text.toString().trim()
            if (relay.isNotEmpty() && deviceId.isNotEmpty()) {
                startConnection(relay, deviceId)
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

    private fun startConnection(relay: String, deviceId: String) {
        Log.i(TAG, "Starting connection: relay=$relay deviceId=$deviceId")

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
        val config = JSONObject().apply {
            put("relays", listOf(relay))
            put("deviceId", deviceId)
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
                if (streamReady && decoderConfigured) {
                    val raw = RustBridge.nativePollVideoFrame(viewerHandle)
                    if (raw != null && raw.size > 8) {
                        val ptsUs = extractPtsUs(raw)
                        val nalData = extractFrameData(raw)
                        val isKeyframe = isKeyframeNal(nalData)
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
                        updateState(if (connType == "direct") "直连" else "已连接 (Relay)")
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

    // ═══════════════════════════════════════════════
    // NAL 工具
    // ═══════════════════════════════════════════════

    /**
     * 判断 NAL 数据是否包含 IDR 关键帧
     * H.265 NAL header: (byte[0] >> 1) & 0x3F
     * IDR_W_RADL=19, IDR_N_LP=20
     */
    private fun isKeyframeNal(nalData: ByteArray): Boolean {
        if (nalData.isEmpty()) return false
        val nalType = (nalData[0].toInt() shr 1) and 0x3F
        return nalType == 19 || nalType == 20
    }
}
