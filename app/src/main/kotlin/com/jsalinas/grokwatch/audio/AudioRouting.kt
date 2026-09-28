package com.jsalinas.grokwatch.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Puts the device in voice-communication mode (best AEC) for the duration of a session. */
class AudioRouting(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var active = false

    fun begin() {
        if (active) return
        active = true
        previousMode = am.mode
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        // Prefer a Bluetooth headset if connected, otherwise the watch speaker.
        runCatching {
            val devices = am.availableCommunicationDevices
            val preferred = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (preferred != null) am.setCommunicationDevice(preferred)
        }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focusRequest = req
        runCatching { am.requestAudioFocus(req) }
    }

    fun end() {
        if (!active) return
        active = false
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
        runCatching { am.clearCommunicationDevice() }
        runCatching { am.mode = previousMode }
    }
}
