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
    //        "enable_mdns":false,"stream_type":"auto","no_audio":false}
    external fun nativeConnect(handle: Long, json: String): Boolean

    // ── 数据轮询（非阻塞, 无帧时返回 null） ──
    // 返回格式: [PTS(8B big-endian i64 µs)] + [H.265 NAL data]
    external fun nativePollVideoFrame(handle: Long): ByteArray?
    // 返回格式: [PTS(8B big-endian i64 µs)] + [PCM 16-bit LE data]
    external fun nativePollAudioFrame(handle: Long): ByteArray?

    // ── 事件 ──
    // 返回 JSON: {"type":"Connected","peer_id":"...","connection_type":"relay"}
    external fun nativePollEvent(handle: Long): String?
}

/**
 * 从 Rust 返回的 PTS 前缀 byte[] 中解析 PTS（微秒）
 */
fun extractPtsUs(data: ByteArray): Long {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
    return buf.long
}

/**
 * 从 Rust 返回的 PTS 前缀 byte[] 中解析帧数据
 */
fun extractFrameData(data: ByteArray): ByteArray {
    return data.sliceArray(8 until data.size)
}
