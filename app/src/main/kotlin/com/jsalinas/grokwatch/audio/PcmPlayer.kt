package com.jsalinas.grokwatch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams mono PCM16 LE audio from the realtime API to an AudioTrack.
 *
 * Tuned for Wear OS watch speakers (Pixel Watch):
 *  - Plays at a device-friendly rate (prefer 48 kHz) with explicit linear resampling
 *    from the API's native 24 kHz, so Android never silently plays at the wrong rate.
 *  - Uses USAGE_ASSISTANT + CONTENT_TYPE_SPEECH (warmer speaker EQ than telephony VOICE_COMM).
 *  - Large MODE_STREAM buffer (~750 ms–1 s) + short prebuffer before [play] to absorb jitter.
 *  - Dedicated writer thread does resampling + AudioTrack writes; [enqueue] stays cheap so
 *    WebSocket callbacks never block on audio I/O.
 *  - Aligns writes to 2-byte samples; supports instant [flush] for barge-in only.
 */
class PcmPlayer(
    /** Sample rate of PCM delivered by the API (typically 24_000). */
    val sourceSampleRate: Int,
) {
    /** Actual AudioTrack sample rate after negotiation (may differ from [sourceSampleRate]). */
    var playSampleRate: Int = sourceSampleRate
        private set

    /** Queue holds *source-rate* PCM16; the writer resamples before writing. */
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val generation = AtomicInteger(0)
    private val framesWritten = AtomicLong(0) // source-rate frames written to the track
    private val framesEnqueued = AtomicLong(0) // source-rate frames accepted by [enqueue]
    @Volatile private var running = false
    @Volatile private var playStarted = false
    @Volatile private var lastAudibleAt = 0L
    @Volatile private var lastEnqueueAt = 0L
    @Volatile private var headBase = 0L
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    /** Leftover odd byte from a prior delta (PCM16 must stay 2-byte aligned). */
    private var pendingOdd: Byte? = null
    /** Coalesces tiny deltas into ~[COALESCE_MS] chunks before queueing (source-rate). */
    private val coalesce = ByteArrayOutputStream(sourceSampleRate * 2 / 5)
    private val coalesceLock = Any()

    fun start() {
        if (running) return
        val opened = openTrack() ?: run {
            Log.e(TAG, "Failed to open AudioTrack at any candidate rate")
            return
        }
        track = opened
        // Do NOT play() yet — fill a short prebuffer first so the first network jitter
        // cannot underrun an empty MODE_STREAM track.
        playStarted = false
        running = true
        thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val maxWrite = (playSampleRate * 2 * WRITE_SLICE_MS / 1000).coerceAtLeast(playSampleRate / 25)
            val prebufferPlayBytes =
                (playSampleRate * 2 * PREBUFFER_MS / 1000).coerceAtLeast(playSampleRate / 10)
            var playBytesBuffered = 0
            var seenGen = generation.get()
            while (running) {
                val genNow = generation.get()
                if (genNow != seenGen) {
                    // Barge-in / flush: restart prebuffer accounting.
                    seenGen = genNow
                    playBytesBuffered = 0
                }
                val polled = queue.poll(30, TimeUnit.MILLISECONDS) ?: drainCoalesceIfIdle()
                if (polled == null) {
                    // Short replies (< prebuffer): start once the producer has gone idle.
                    maybeStartPlayback(
                        opened, playBytesBuffered, prebufferPlayBytes, force = producerIdle(),
                    )
                    continue
                }
                val chunk = polled
                val gen = generation.get()
                // Resample on the writer thread so enqueue()/WS callbacks stay cheap.
                val playPcm = if (playSampleRate == sourceSampleRate) {
                    chunk
                } else {
                    resamplePcm16Le(chunk, sourceSampleRate, playSampleRate)
                }
                if (playPcm.isEmpty()) continue
                var off = 0
                while (running && off < playPcm.size && gen == generation.get()) {
                    var len = minOf(maxWrite, playPcm.size - off)
                    if (len and 1 != 0) len -= 1
                    if (len <= 0) break
                    val n = opened.write(playPcm, off, len)
                    if (n <= 0) {
                        Log.w(TAG, "AudioTrack.write error $n")
                        break
                    }
                    off += n
                    playBytesBuffered += n
                    if (gen == generation.get()) {
                        val playFrames = n / 2
                        val sourceFrames = playFrames.toLong() * sourceSampleRate / playSampleRate
                        framesWritten.addAndGet(sourceFrames)
                    }
                    lastAudibleAt = SystemClock.elapsedRealtime()
                    maybeStartPlayback(opened, playBytesBuffered, prebufferPlayBytes, force = false)
                }
                // Short reply that never reached prebuffer: start as soon as the producer idles.
                if (running && gen == generation.get()) {
                    maybeStartPlayback(opened, playBytesBuffered, prebufferPlayBytes, force = producerIdle())
                }
            }
        }, "grok-player").apply { start() }
        Log.i(
            TAG,
            "Started: source=${sourceSampleRate}Hz play=${playSampleRate}Hz " +
                "buffer=${opened.bufferSizeInFrames}frames prebuffer=${PREBUFFER_MS}ms",
        )
    }

    private fun producerIdle(): Boolean {
        if (queue.isNotEmpty()) return false
        synchronized(coalesceLock) { if (coalesce.size() >= 2) return false }
        val idleFor = SystemClock.elapsedRealtime() - lastEnqueueAt
        return lastEnqueueAt > 0L && idleFor >= COALESCE_MS.toLong() + 30L
    }

    private fun maybeStartPlayback(
        opened: AudioTrack,
        playBytesBuffered: Int,
        prebufferPlayBytes: Int,
        force: Boolean,
    ) {
        if (playStarted || playBytesBuffered < 2) return
        if (!force && playBytesBuffered < prebufferPlayBytes) return
        runCatching {
            if (opened.playState != AudioTrack.PLAYSTATE_PLAYING) {
                opened.play()
            }
        }
        playStarted = true
        Log.d(TAG, "Playback started after ${playBytesBuffered * 1000 / (playSampleRate * 2)}ms buffered")
    }

    private fun openTrack(): AudioTrack? {
        // Prefer 48 kHz (typical hardware mixer rate on modern Wear) so we control
        // resampling quality ourselves instead of relying on a silent/poor platform path.
        val candidates = linkedSetOf(48_000, sourceSampleRate, 44_100, 32_000, 16_000)
            .filter { it > 0 }
        for (rate in candidates) {
            val min = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min <= 0) {
                Log.w(TAG, "getMinBufferSize($rate)=$min, skipping")
                continue
            }
            // ~750 ms–1 s of mono PCM16 (or 6× min) — absorbs Wear WS/jitter without huge latency
            // because we only *prebuffer* ~PREBUFFER_MS before play(); the rest is headroom.
            val oneSecondBytes = rate * 2 // mono PCM16
            val bufferBytes = maxOf(min * 6, (oneSecondBytes * 3) / 4, oneSecondBytes)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack($rate) build failed", e)
                null
            }
            if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                playSampleRate = rate
                return t
            }
            Log.w(TAG, "AudioTrack($rate) not initialized (state=${t?.state})")
            runCatching { t?.release() }
        }
        // Last resort: USAGE_VOICE_COMMUNICATION at source rate.
        val min = AudioTrack.getMinBufferSize(
            sourceSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) return null
        return try {
            val oneSecondBytes = sourceSampleRate * 2
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sourceSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min * 6, (oneSecondBytes * 3) / 4, oneSecondBytes))
                .build()
                .also { playSampleRate = sourceSampleRate }
                .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback AudioTrack failed", e)
            null
        }
    }

    /**
     * Accepts source-rate PCM16. Cheap on the caller thread: align, coalesce, offer.
     * Resampling and AudioTrack I/O happen on the writer thread.
     */
    fun enqueue(pcm: ByteArray) {
        if (!running || pcm.isEmpty()) return
        val toPlay = synchronized(coalesceLock) {
            val sourceAligned = alignAndBuffer(pcm) ?: return
            framesEnqueued.addAndGet((sourceAligned.size / 2).toLong())
            lastEnqueueAt = SystemClock.elapsedRealtime()
            lastAudibleAt = lastEnqueueAt
            drainCoalesceChunksLocked(forceAll = false)
        }
        for (chunk in toPlay) queue.offer(chunk)
    }

    /** Align to 2-byte samples and append to the coalesce buffer. Returns newly added source bytes. */
    private fun alignAndBuffer(pcm: ByteArray): ByteArray? {
        var data = pcm
        pendingOdd?.let { odd ->
            val merged = ByteArray(1 + data.size)
            merged[0] = odd
            System.arraycopy(data, 0, merged, 1, data.size)
            data = merged
            pendingOdd = null
        }
        if (data.size and 1 != 0) {
            pendingOdd = data.last()
            data = data.copyOf(data.size - 1)
        }
        if (data.isEmpty()) return null
        coalesce.write(data)
        return data
    }

    /**
     * Pulls complete [COALESCE_MS] windows (or everything when [forceAll]) out of the coalesce
     * buffer as *source-rate* PCM (resampling is deferred to the writer).
     */
    private fun drainCoalesceChunksLocked(forceAll: Boolean): List<ByteArray> {
        val target = sourceSampleRate * 2 * COALESCE_MS / 1000
        val out = ArrayList<ByteArray>(2)
        while (coalesce.size() >= target || (forceAll && coalesce.size() >= 2)) {
            val take = if (forceAll && coalesce.size() < target) {
                coalesce.size() and 1.inv() // even
            } else {
                target
            }
            if (take < 2) break
            val all = coalesce.toByteArray()
            val chunk = all.copyOf(take)
            coalesce.reset()
            if (all.size > take) coalesce.write(all, take, all.size - take)
            if (chunk.isNotEmpty()) out += chunk
            if (forceAll && coalesce.size() < target) {
                if (coalesce.size() < 2) {
                    coalesce.reset()
                    break
                }
            }
        }
        return out
    }

    /** If the producer paused briefly, push the partial coalesce so the tail of a reply isn't lost. */
    private fun drainCoalesceIfIdle(): ByteArray? {
        val idleFor = SystemClock.elapsedRealtime() - lastEnqueueAt
        if (idleFor < COALESCE_MS.toLong() + 20L) return null
        val drained = synchronized(coalesceLock) { drainCoalesceChunksLocked(forceAll = true) }
        if (drained.isEmpty()) return null
        for (i in 1 until drained.size) queue.offer(drained[i])
        return drained[0]
    }

    /** Frames that have actually been played out since the last flush (source-rate units). */
    fun playedFrames(): Long {
        val playFrames = rawHead() - headBase
        return if (playSampleRate == sourceSampleRate) {
            playFrames
        } else {
            playFrames * sourceSampleRate / playSampleRate
        }
    }

    private fun rawHead(): Long =
        runCatching { (track?.playbackHeadPosition ?: 0).toLong() and 0xFFFFFFFFL }.getOrDefault(0L)

    fun writtenFrames(): Long = framesWritten.get()

    /** Frames handed to [enqueue] since the last flush (played or not), in source-rate units. */
    fun enqueuedFrames(): Long = framesEnqueued.get()

    /** True while queued or buffered audio is still being played (plus a short tail). */
    fun isAudible(tailMs: Long = 400): Boolean {
        if (!playStarted && (queue.isNotEmpty() || framesEnqueued.get() > 0)) return true
        if (queue.isNotEmpty()) return true
        synchronized(coalesceLock) { if (coalesce.size() >= 2) return true }
        if (playedFrames() < writtenFrames()) return true
        return lastAudibleAt > 0L && SystemClock.elapsedRealtime() - lastAudibleAt < tailMs
    }

    /** Drops everything queued/buffered immediately (barge-in). Does not run on half-duplex mic gate. */
    fun flush() {
        generation.incrementAndGet()
        queue.clear()
        synchronized(coalesceLock) {
            pendingOdd = null
            coalesce.reset()
        }
        track?.let {
            runCatching { it.pause(); it.flush() }
            // Stay paused until the next utterance prebuffers — avoids underrun hiss after barge-in.
        }
        playStarted = false
        headBase = rawHead()
        framesWritten.set(0)
        framesEnqueued.set(0)
        lastAudibleAt = 0L
        lastEnqueueAt = 0L
    }

    fun stop() {
        running = false
        generation.incrementAndGet()
        queue.clear()
        synchronized(coalesceLock) {
            pendingOdd = null
            coalesce.reset()
        }
        runCatching { track?.pause(); track?.flush() }
        thread?.join(500)
        thread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        playStarted = false
    }

    companion object {
        private const val TAG = "PcmPlayer"
        /** Coalesce API deltas into chunks of this many ms before queueing (reduces underruns). */
        private const val COALESCE_MS = 50
        private const val WRITE_SLICE_MS = 100
        /** Hold this many ms of play-rate audio in the track before calling play(). */
        private const val PREBUFFER_MS = 200

        /**
         * Linear-interpolation resample of mono PCM16 LE. Prefer this over letting Android
         * silently play at the wrong rate (tinny / harsh / pitch-shifted).
         */
        fun resamplePcm16Le(input: ByteArray, fromRate: Int, toRate: Int): ByteArray {
            if (fromRate == toRate || input.size < 2) return input
            val inSamples = input.size / 2
            val outSamples = (inSamples.toLong() * toRate / fromRate).toInt().coerceAtLeast(1)
            val inBuf = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val out = ByteArray(outSamples * 2)
            val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            // Exact 2× upsample (24 kHz → 48 kHz): hold + midpoint lerp.
            if (toRate == fromRate * 2) {
                var written = 0
                for (i in 0 until inSamples) {
                    val s0 = inBuf.get(i).toInt()
                    outBuf.putShort(s0.toShort())
                    written++
                    if (written >= outSamples) break
                    val s1 = if (i + 1 < inSamples) inBuf.get(i + 1).toInt() else s0
                    outBuf.putShort(((s0 + s1) / 2).toShort())
                    written++
                    if (written >= outSamples) break
                }
                return if (written * 2 == out.size) out else out.copyOf(written * 2)
            }
            for (i in 0 until outSamples) {
                val srcPos = i.toDouble() * fromRate / toRate
                val i0 = srcPos.toInt().coerceIn(0, inSamples - 1)
                val i1 = (i0 + 1).coerceAtMost(inSamples - 1)
                val frac = srcPos - i0
                val s = inBuf.get(i0) * (1.0 - frac) + inBuf.get(i1) * frac
                outBuf.putShort(
                    s.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                )
            }
            return out
        }
    }
}
