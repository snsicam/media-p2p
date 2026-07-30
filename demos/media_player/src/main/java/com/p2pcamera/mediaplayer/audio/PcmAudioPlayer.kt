package com.p2pcamera.mediaplayer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * PCM 音频播放器 (AudioTrack)
 *
 * 参数应与 DeviceCam 编码配置一致:
 *  - 16kHz 采样率
 *  - Mono 单声道
 *  - PCM 16-bit LE
 */
class PcmAudioPlayer {

    companion object {
        private const val TAG = "PcmAudioPlayer"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SIZE = 640 // 20ms @ 16kHz mono 16bit = 320 samples * 2
        private const val BUFFER_SIZE = FRAME_SIZE * 4 // 4 x 20ms = 80ms
    }

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL)
                .setEncoding(ENCODING)
                .build()
        )
        .setBufferSizeInBytes(BUFFER_SIZE)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    private var started = false

    var totalFrames = 0L
        private set

    /**
     * 开始播放
     */
    fun play() {
        if (!started) {
            track.play()
            started = true
            Log.i(TAG, "AudioTrack started")
        }
    }

    /**
     * 写入 PCM 数据（16-bit LE, mono, 16kHz）
     *
     * @param pcmData 原始 PCM 数据（已去除 PTS 前缀）
     */
    fun write(pcmData: ByteArray) {
        if (!started) play()
        val written = track.write(pcmData, 0, pcmData.size)
        if (written > 0) {
            totalFrames++
        } else if (written < 0) {
            Log.w(TAG, "AudioTrack write error: $written")
        }
    }

    /**
     * 暂停播放
     */
    fun pause() {
        if (started) {
            track.pause()
            started = false
        }
    }

    /**
     * 释放音频播放器
     */
    fun release() {
        try {
            track.stop()
        } catch (_: Exception) {}
        track.release()
        Log.i(TAG, "AudioTrack released, total frames: $totalFrames")
    }
}
