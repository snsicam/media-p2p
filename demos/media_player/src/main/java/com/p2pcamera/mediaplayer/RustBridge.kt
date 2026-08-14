package com.p2pcamera.mediaplayer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JNI 桥接 — 对应 Rust jni_bridge.rs 的导出函数。
 *
 * 句柄管理: Rust 侧维护全局句柄表，nativeCreate 返回 index 作为 Long handle，
 * 所有后续调用都传入该 handle。
 */
object RustBridge {
    init {
        System.loadLibrary("mobile_core")
    }

    // ── 生命周期 ──
    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)

    // ── 控制 ──
    // json: {"relays":["/ip4/.../tcp/.../p2p/..."],"deviceId":"12D3...",
    //        "enable_mdns":false,"stream_type":"auto","no_audio":false,
    //        "serial_map":{"<sn>":"<peerid>",...}}  // 可选: 本地解析 SN 走 LAN 直连
    external fun nativeConnect(handle: Long, json: String): Boolean

    // 设备配置下发 (控制通道): 传入 ControlRequest JSON, 返回 ControlResponse JSON
    // 同步阻塞 (最长 5s), 调用方须在后台线程执行
    external fun nativeSendControlCommand(handle: Long, json: String): String?

    // ── 抓拍文件查询 / 下载 (系统 tab) ──
    // 查询设备已合成的 AVI 文件列表, 返回 JSON: {"ok":true,"files":["a.avi",...]}
    // 同步阻塞 (最长 5s), 调用方须在后台线程执行
    external fun nativeListSnapshots(handle: Long): String?

    // 下载指定 AVI 到本地目录, 返回 JSON: {"ok":true,"size":123,"path":"..."}
    // 同步阻塞 (最长 60s), 调用方须在后台线程执行
    external fun nativeDownloadFile(handle: Long, name: String, destDir: String): String?

    // ── 数据轮询（非阻塞, 无帧时返回 null） ──
    // 视频返回格式: [PTS(8B big-endian i64 µs)] + [flags(1B)] + [H.265 NAL data]
    //   flags bit 2 (0x04) = 关键帧, 由 Rust viewer 接收端字节扫描判定 (cam 不传, 不可靠)
    //   解析见 extractVideoPtsUs / extractVideoFlags / extractVideoFrameData
    external fun nativePollVideoFrame(handle: Long): ByteArray?
    // 音频返回格式: [PTS(8B big-endian i64 µs)] + [PCM 16-bit LE data] (offset 8 起为数据)
    external fun nativePollAudioFrame(handle: Long): ByteArray?

    // ── 事件 ──
    // 返回 JSON: {"type":"Connected","peer_id":"...","connection_type":"relay"}
    external fun nativePollEvent(handle: Long): String?
}

/**
 * 从 Rust 返回的 PTS 前缀 byte[] 中解析 PTS（微秒）。
 * 视频/音频帧前 8 字节均为 PTS，故通用。
 */
fun extractPtsUs(data: ByteArray): Long {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
    return buf.long
}

/**
 * 从 Rust 返回的视频帧 byte[] 中解析 flags 字节（offset 8）。
 * bit 2 (0x04) = FLAG_VIDEO_KEYFRAME（关键帧）。
 */
fun extractVideoFlags(data: ByteArray): Int {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
    buf.long // 跳过 PTS 8 字节
    return buf.get().toInt() and 0xFF
}

/**
 * 从 Rust 返回的视频帧 byte[] 中提取 H.265 NAL 数据。
 * 视频帧格式: [PTS 8B] + [flags 1B] + [data]，故数据从 offset 9 开始。
 */
fun extractVideoFrameData(data: ByteArray): ByteArray {
    return data.sliceArray(9 until data.size)
}

/**
 * 从 Rust 返回的 PTS 前缀 byte[] 中解析音频帧数据（offset 8 起）。
 */
fun extractFrameData(data: ByteArray): ByteArray {
    return data.sliceArray(8 until data.size)
}
