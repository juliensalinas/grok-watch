package com.jsalinas.grokwatch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams mono PCM16 audio (as delivered by the realtime API) to an AudioTrack.
 * Supports instant [flush] for barge-in and reports whether audio is still audible.
 */
class PcmPlayer(val sampleRate: Int) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val generation = AtomicInteger(0)
    private val framesWritten = AtomicLong(0)
    private val framesEnqueued = AtomicLong(0)
    @Volatile private var running = false
    @Volatile private var lastAudibleAt = 0L
    @Volatile private var headBase = 0L
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min * 2, sampleRate * 2 / 5))
            .build()
        track = t
        t.play()
        running = true
        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val maxWrite = sampleRate * 2 * 20 / 1000 // write in 20 ms slices so flush() is responsive
            while (running) {
                val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val gen = generation.get()
                var off = 0
                while (running && off < chunk.size && gen == generation.get()) {
                    val len = minOf(maxWrite, chunk.size - off)
                    val n = t.write(chunk, off, len)
                    if (n <= 0) { Log.w(TAG, "AudioTrack.write error $n"); break }
                    off += n
                    if (gen == generation.get()) framesWritten.addAndGet((n / 2).toLong())
                    lastAudibleAt = SystemClock.elapsedRealtime()
                }
            }
        }, "grok-player").apply { start() }
    }

    fun enqueue(pcm: ByteArray) {
        if (running && pcm.isNotEmpty()) {
            queue.offer(pcm)
            framesEnqueued.addAndGet((pcm.size / 2).toLong())
            lastAudibleAt = SystemClock.elapsedRealtime()
        }
    }

    /** Frames that have actually been played out since the last flush. */
    fun playedFrames(): Long = rawHead() - headBase

    private fun rawHead(): Long =
        runCatching { (track?.playbackHeadPosition ?: 0).toLong() and 0xFFFFFFFFL }.getOrDefault(0L)

    fun writtenFrames(): Long = framesWritten.get()

    /** Frames handed to [enqueue] since the last flush (played or not). */
    fun enqueuedFrames(): Long = framesEnqueued.get()

    /** True while queued or buffered audio is still being played (plus a short tail). */
    fun isAudible(tailMs: Long = 250): Boolean {
        if (queue.isNotEmpty()) return true
        if (playedFrames() < writtenFrames()) return true
        return SystemClock.elapsedRealtime() - lastAudibleAt < tailMs
    }

    /** Drops everything queued/buffered immediately (barge-in). */
    fun flush() {
        generation.incrementAndGet()
        queue.clear()
        track?.let {
            runCatching { it.pause(); it.flush(); it.play() }
        }
        headBase = rawHead()
        framesWritten.set(0)
        framesEnqueued.set(0)
        lastAudibleAt = 0L
    }

    fun stop() {
        running = false
        generation.incrementAndGet()
        queue.clear()
        runCatching { track?.pause(); track?.flush() }
        thread?.join(500)
        thread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    companion object { private const val TAG = "PcmPlayer" }
}
