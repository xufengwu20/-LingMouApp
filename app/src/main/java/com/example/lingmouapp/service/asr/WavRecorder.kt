package com.example.lingmouapp.service.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * 极简录音器：录制一段话，检测到尾部静音或达到时长上限就返回 WAV 字节。
 *
 * 这是「够用的 VAD」：不算科学，但足以把一句话切出来丢给云端 ASR。
 * 全程只放在内存里，不落盘。
 */
class WavRecorder(private val sampleRate: Int = DEFAULT_SAMPLE_RATE) {

    @SuppressLint("MissingPermission")
    fun recordUtterance(
        maxDurationMs: Long = MAX_DURATION_MS,
        silenceStopMs: Long = SILENCE_STOP_MS,
        onLevel: (Float) -> Unit = {}
    ): ByteArray? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null

        val bufferBytes = maxOf(minBuffer, sampleRate / 5 * 2)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (e: Exception) {
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release()
            } catch (e: Exception) {
                // ignore
            }
            return null
        }

        val pcm = ByteArrayOutputStream()
        val buffer = ShortArray(bufferBytes / 2)
        val startedAt = SystemClock.elapsedRealtime()
        var lastVoiceAt = startedAt
        var heardVoice = false

        return try {
            record.startRecording()
            while (true) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) break

                val now = SystemClock.elapsedRealtime()
                var squareSum = 0.0
                for (i in 0 until read) {
                    val v = buffer[i].toInt()
                    squareSum += (v * v).toDouble()
                }
                val rms = sqrt(squareSum / read).toFloat()
                onLevel((rms / 32768f).coerceIn(0f, 1f))

                if (rms > VOICE_RMS_THRESHOLD) {
                    heardVoice = true
                    lastVoiceAt = now
                }

                for (i in 0 until read) {
                    val v = buffer[i].toInt()
                    pcm.write(v and 0xFF)
                    pcm.write((v shr 8) and 0xFF)
                }

                if (now - startedAt >= maxDurationMs) break
                if (heardVoice && now - lastVoiceAt >= silenceStopMs) break
            }
            try {
                record.stop()
            } catch (e: Exception) {
                // ignore
            }
            val data = pcm.toByteArray()
            if (!heardVoice || data.isEmpty()) null else wrapWav(data)
        } catch (e: Exception) {
            null
        } finally {
            try {
                record.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun wrapWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dataLen = pcm.size
        val byteRate = sampleRate * 2

        out.write("RIFF".toByteArray())
        out.write(intLe(36 + dataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intLe(16))
        out.write(shortLe(1))            // PCM
        out.write(shortLe(1))            // 单声道
        out.write(intLe(sampleRate))
        out.write(intLe(byteRate))
        out.write(shortLe(2))            // block align
        out.write(shortLe(16))           // 位深
        out.write("data".toByteArray())
        out.write(intLe(dataLen))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val MAX_DURATION_MS = 8_000L
        const val SILENCE_STOP_MS = 700L
        const val VOICE_RMS_THRESHOLD = 900f
    }
}
