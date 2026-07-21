package com.p2pcamera.mediaplayer.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.265 硬件解码器 (MediaCodec Surface 零拷贝模式)
 *
 * 输入: H.265 NAL 单元 (VPS/SPS/PPS + IDR/P/B frames)
 * 输出: 直接渲染到 Surface
 */
class H265Decoder(private val width: Int = 1920, private val height: Int = 1080) {

    companion object {
        private const val TAG = "H265Decoder"
        private const val MIME_TYPE = "video/hevc"

        // NAL unit type: (byte[0] >> 1) & 0x3F
        private const val NAL_VPS = 32
        private const val NAL_SPS = 33
        private const val NAL_PPS = 34
        private const val NAL_IDR_W_RADL = 19
        private const val NAL_IDR_N_LP = 20
    }

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var started = false
    private var csdBuilt = false
    private val csd0 = ByteBuffer.allocate(65536)   // VPS+SPS+PPS
    private val bufferInfo = MediaCodec.BufferInfo()
    private var fpsClock: Long = 0
    private var csdWaitLogCount = 0

    // 统计
    var frameCount = 0L
        private set
    var lastPtsUs = 0L
        private set

    /**
     * 配置解码器并绑定 Surface，不调用 start()
     */
    fun configure(surface: Surface) {
        this.surface = surface
        releaseInternal()

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
        }

        val codecName = selectCodec(MIME_TYPE)
            ?: throw IllegalStateException("No H.265 hardware decoder found")
        Log.i(TAG, "Selected codec: $codecName")

        codec = MediaCodec.createByCodecName(codecName).apply {
            // 同步模式: 由 feedFrame 主动 dequeue/queue，并在喂帧后 drain 输出渲染到 Surface
            configure(format, surface, null, 0)
        }
        csdBuilt = false
        Log.i(TAG, "Codec configured (sync mode): $codecName")
    }

    /**
     * 启动解码器，必须在 configure() 之后调用
     */
    fun start() {
        codec?.start()
        started = true
        Log.i(TAG, "Decoder started")
    }

    /**
     * 喂入一帧 H.265 NAL 数据（已去除 PTS 前缀的原始 NAL 数据）
     *
     * @param nalBytes  NAL 单元数据
     * @param ptsUs     显示时间戳（微秒）
     * @param isKeyframe 是否为 IDR 关键帧
     */
    fun feedFrame(nalBytes: ByteArray, ptsUs: Long, isKeyframe: Boolean) {
        val codec = codec ?: return
        if (!started) return
        try {
            // CSD 收集: 首帧前需要 VPS+SPS+PPS
            if (!csdBuilt) {
                collectCsd(nalBytes)
                if (!csdBuilt) {
                    if (csdWaitLogCount < 20) {
                        csdWaitLogCount++
                        Log.d(TAG, "Waiting for CSD (VPS/SPS/PPS)... frame#$csdWaitLogCount")
                    }
                    return
                }
                // CSD 收集完毕，发送 Codec-Specific Data
                sendCsd(codec)
                Log.i(TAG, "CSD sent, decoder ready for frames")
            }

            val inputIndex = codec.dequeueInputBuffer(10_000) // 10ms timeout
            if (inputIndex < 0) {
                Log.w(TAG, "dequeueInputBuffer timeout, dropping frame")
                return
            }

            val inputBuffer = codec.getInputBuffer(inputIndex)!!
            inputBuffer.clear()
            inputBuffer.put(nalBytes)

            var flags = 0
            if (isKeyframe) {
                flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }

            codec.queueInputBuffer(inputIndex, 0, nalBytes.size, ptsUs, flags)

            // 同步模式: 主动 drain 输出并渲染到 Surface
            drainOutputs(codec)

            // 首帧时钟
            if (fpsClock == 0L) fpsClock = ptsUs
        } catch (e: Exception) {
            Log.e(TAG, "feedFrame error: ${e.message}", e)
        }
    }

    /**
     * 同步模式: 取出所有已解码输出 buffer 并渲染到 Surface
     */
    private fun drainOutputs(codec: MediaCodec) {
        var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        while (outIndex >= 0) {
            codec.releaseOutputBuffer(outIndex, true) // true = render to surface
            lastPtsUs = bufferInfo.presentationTimeUs
            frameCount++
            if (frameCount <= 3 || frameCount % 100 == 0L) {
                Log.i(TAG, "Rendered frame #$frameCount pts=${bufferInfo.presentationTimeUs}")
            }
            outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val f = codec.outputFormat
            val w = f.getInteger(MediaFormat.KEY_WIDTH)
            val h = f.getInteger(MediaFormat.KEY_HEIGHT)
            Log.i(TAG, "Output format changed: ${w}x${h}")
        }
    }

    /**
     * 释放解码器
     */
    fun release() {
        releaseInternal()
        Log.i(TAG, "Decoder released, total frames: $frameCount")
    }

    // ── private ──

    private fun releaseInternal() {
        started = false
        try {
            codec?.stop()
        } catch (_: Exception) {}
        try {
            codec?.release()
        } catch (_: Exception) {}
        codec = null
    }

    /**
     * 从 NAL 数据中收集 VPS/SPS/PPS 到 CSD-0 buffer
     */
    private fun collectCsd(nalBytes: ByteArray) {
        if (nalBytes.isEmpty()) return
        var i = 0
        val len = nalBytes.size
        // RK VENC (pack_mode=0) 一个 pack 内含多个 NAL (VPS/SPS/PPS/IDR...) 拼接,
        // 每个 NAL 前带 Annex-B 起始码 (00 00 00 01 或 00 00 01)。
        // 必须跳过起始码逐 NAL 扫描, 不能只看 nalBytes[0] (那是起始码的 0x00)。
        while (i + 2 < len) {
            val b0 = nalBytes[i].toInt() and 0xFF
            val b1 = nalBytes[i + 1].toInt() and 0xFF
            val b2 = nalBytes[i + 2].toInt() and 0xFF
            val has4 = i + 3 < len
            val b3 = if (has4) nalBytes[i + 3].toInt() and 0xFF else -1
            val hdr: Int
            if (has4 && b0 == 0 && b1 == 0 && b2 == 0 && b3 == 1) {
                hdr = i + 4
            } else if (b0 == 0 && b1 == 0 && b2 == 1) {
                hdr = i + 3
            } else {
                i++
                continue
            }
            if (hdr >= len) break
            val nalType = (nalBytes[hdr].toInt() shr 1) and 0x3F
            if (nalType == NAL_VPS || nalType == NAL_SPS || nalType == NAL_PPS) {
                // 找下一个起始码作为本 NAL 的结束边界
                var end = hdr + 1
                while (end + 2 < len) {
                    val e0 = nalBytes[end].toInt() and 0xFF
                    val e1 = nalBytes[end + 1].toInt() and 0xFF
                    val e2 = nalBytes[end + 2].toInt() and 0xFF
                    val e3 = if (end + 3 < len) nalBytes[end + 3].toInt() and 0xFF else -1
                    if ((e0 == 0 && e1 == 0 && e2 == 0 && e3 == 1) ||
                        (e0 == 0 && e1 == 0 && e2 == 1)) {
                        break
                    }
                    end++
                }
                if (end + 2 >= len) end = len
                // 追加到 CSD-0: 4 字节起始码 + NAL 数据
                csd0.put(0.toByte()); csd0.put(0.toByte()); csd0.put(0.toByte()); csd0.put(1.toByte())
                csd0.put(nalBytes, hdr, end - hdr)
                Log.i(TAG, "CSD NAL type: $nalType (${end - hdr} bytes)")
                if (nalType == NAL_PPS) {
                    csd0.flip()
                    csdBuilt = true
                    Log.i(TAG, "CSD-0 built: ${csd0.limit()} bytes (VPS+SPS+PPS)")
                }
            }
            i = hdr
        }
    }

    /**
     * 将收集的 CSD-0 发送到解码器
     */
    private fun sendCsd(codec: MediaCodec) {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) {
            Log.e(TAG, "Failed to dequeue input buffer for CSD")
            return
        }
        val inputBuffer = codec.getInputBuffer(inputIndex)!!
        val csdBytes = ByteArray(csd0.limit())
        csd0.rewind()
        csd0.get(csdBytes)
        csd0.rewind()

        inputBuffer.clear()
        inputBuffer.put(csdBytes)
        codec.queueInputBuffer(
            inputIndex, 0, csdBytes.size, 0,
            MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        )
        Log.i(TAG, "CSD-0 queued (${csdBytes.size} bytes)")
    }

    /**
     * 选择 H.265 硬解解码器
     */
    private fun selectCodec(mimeType: String): String? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder && codecInfo.supportedTypes.contains(mimeType)) {
                val caps = codecInfo.getCapabilitiesForType(mimeType)
                val supportsSurface = caps.colorFormats?.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                ) ?: false
                if (supportsSurface && codecInfo.name.lowercase().contains("hevc")) {
                    // 优选硬件解码器
                    if (!codecInfo.name.lowercase().contains("software") &&
                        !codecInfo.name.lowercase().contains("google")) {
                        return codecInfo.name
                    }
                }
            }
        }
        // 回退: 任意支持 Surface 的 H.265 解码器
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder && codecInfo.supportedTypes.contains(mimeType)) {
                val caps = codecInfo.getCapabilitiesForType(mimeType)
                if (caps.colorFormats?.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) == true) {
                    return codecInfo.name
                }
            }
        }
        return null
    }
}
