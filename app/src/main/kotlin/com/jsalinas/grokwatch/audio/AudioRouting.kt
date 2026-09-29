package com.jsalinas.grokwatch.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Session audio routing for Wear OS voice chat.
 *
 * Keeps [AudioManager.MODE_IN_COMMUNICATION] so the mic's VOICE_COMMUNICATION source + AEC work,
 * but prefers the built-in speaker (not BT SCO) when no headset is connected — SCO / telephony
 * downlink EQ is a common cause of tinny watch-speaker playback. Playback itself uses
 * [AudioAttributes.USAGE_ASSISTANT] (see [PcmPlayer]) so the speaker path isn't forced through
 * the narrowband voice-call route.
 *
 * Volume for ASSISTANT/SPEECH AudioTrack playback is controlled via [STREAM_MUSIC] (the stream
 * Android maps USAGE_ASSISTANT onto for volume keys / AudioManager).
 */
class AudioRouting(private val context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var active = false
    private var volumeReceiver: BroadcastReceiver? = null
    private var onVolumeChanged: ((Int) -> Unit)? = null

    /** Stream matching PcmPlayer's USAGE_ASSISTANT + CONTENT_TYPE_SPEECH AudioTrack. */
    val playbackStream: Int = AudioManager.STREAM_MUSIC

    fun begin(preferredVolumePercent: Int? = null) {
        if (active) return
        active = true
        previousMode = am.mode
        // Communication mode enables hardware AEC for the mic; safe on watch when we pin the
        // output device to the built-in speaker (or a real BT headset), not SCO-only.
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        runCatching {
            val devices = am.availableCommunicationDevices
            val headset = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val preferred = headset ?: speaker
            if (preferred != null) am.setCommunicationDevice(preferred)
        }
        // Match PcmPlayer: ASSISTANT + SPEECH gets a fuller speaker EQ than VOICE_COMMUNICATION.
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .build()
        focusRequest = req
        runCatching { am.requestAudioFocus(req) }
        preferredVolumePercent?.let { setVolumePercent(it) }
    }

    fun end() {
        if (!active) return
        active = false
        stopWatchingVolume()
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
        runCatching { am.clearCommunicationDevice() }
        runCatching { am.mode = previousMode }
    }

    fun getVolumePercent(): Int {
        val max = am.getStreamMaxVolume(playbackStream).coerceAtLeast(1)
        val cur = am.getStreamVolume(playbackStream).coerceIn(0, max)
        return ((cur * 100f) / max).toInt().coerceIn(0, 100)
    }

    fun setVolumePercent(percent: Int): Int {
        val max = am.getStreamMaxVolume(playbackStream).coerceAtLeast(1)
        val level = ((percent.coerceIn(0, 100) / 100f) * max).toInt().coerceIn(0, max)
        runCatching {
            am.setStreamVolume(playbackStream, level, 0)
        }
        return ((level * 100f) / max).toInt().coerceIn(0, 100)
    }

    /** One step up/down on the playback stream (same stream AudioTrack uses via ASSISTANT→MUSIC). */
    fun adjustVolume(direction: Int): Int {
        val adj = when {
            direction > 0 -> AudioManager.ADJUST_RAISE
            direction < 0 -> AudioManager.ADJUST_LOWER
            else -> AudioManager.ADJUST_SAME
        }
        runCatching {
            am.adjustStreamVolume(playbackStream, adj, 0)
        }
        return getVolumePercent()
    }

    /**
     * Listen for physical crown / system volume changes on [playbackStream] and invoke [listener]
     * with the new percent. Call [stopWatchingVolume] / [end] to unregister.
     */
    fun startWatchingVolume(listener: (Int) -> Unit) {
        stopWatchingVolume()
        onVolumeChanged = listener
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != VOLUME_CHANGED_ACTION) return
                val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                if (stream != playbackStream) return
                listener(getVolumePercent())
            }
        }
        volumeReceiver = receiver
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun stopWatchingVolume() {
        volumeReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        volumeReceiver = null
        onVolumeChanged = null
    }

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }
}
