package com.jsalinas.grokwatch.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Captures mono PCM16 little-endian audio from the mic on a background thread.
 *
 * Uses the VOICE_COMMUNICATION source, which enables the platform's echo-cancelled (AEC) voice
 * path, and additionally attaches AcousticEchoCanceler / NoiseSuppressor when the device has them.
 */
class MicRecorder(
    private val preferredRates: IntArray = intArrayOf(16_000, 24_000, 48_000),
    private val chunkMs: Int = 40,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    /** Sample rate actually in use, valid after [start] returns true. */
    var sampleRate: Int = preferredRates.first()
        private set

    /**
     * Starts capture. [onChunk] is invoked on the capture thread with a fresh PCM16 buffer.
     * Returns false if no configuration could be initialised (e.g. mic busy).
     */
    @SuppressLint("MissingPermission") // Caller checks RECORD_AUDIO before starting.
    fun start(onChunk: (ByteArray) -> Unit): Boolean {
        if (running) return true
        val rec = createRecord() ?: return false
        record = rec
        attachEffects(rec.audioSessionId)
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startRecording failed", e)
            release()
            return false
        }
        running = true
        val bytesPerChunk = sampleRate * 2 * chunkMs / 1000
        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                val buf = ByteArray(bytesPerChunk)
                var off = 0
                while (running && off < bytesPerChunk) {
                    val n = rec.read(buf, off, bytesPerChunk - off)
                    if (n <= 0) {
                        if (n < 0) Log.w(TAG, "AudioRecord.read error $n")
                        break
                    }
                    off += n
                }
                if (!running) break
                // PCM16 must be 2-byte aligned; drop a trailing odd byte rather than send a torn sample.
                val even = off and 1.inv()
                if (even > 0) onChunk(if (even == bytesPerChunk) buf else buf.copyOf(even))
            }
        }, "grok-mic").apply { start() }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        for (rate in preferredRates) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(min * 2, rate * 2 / 5), // >= 200 ms of buffering
                )
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord($rate) failed", e); null
            }
            if (rec?.state == AudioRecord.STATE_INITIALIZED) {
                sampleRate = rate
                return rec
            }
            rec?.release()
        }
        return null
    }

    private fun attachEffects(sessionId: Int) {
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.let { it.enabled = true; effects += it }
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.let { it.enabled = true; effects += it }
        }
        runCatching {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.let { it.enabled = true; effects += it }
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        thread?.join(500)
        thread = null
        release()
    }

    private fun release() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        runCatching { record?.release() }
        record = null
    }

    companion object { private const val TAG = "MicRecorder" }
}
