package com.aura.soundboard.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Backend Internal Voice Uplink & Communication Sink Resolver.
 * Never forces loudspeaker to 100% volume.
 * Queries the Android Audio HAL for internal telephony/voice-tx or headset communication sinks.
 */
class AudioFocusController(
    context: Context,
    @Suppress("UNUSED_PARAMETER") private val onFocusLostCallback: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Searches Android's Audio HAL output sinks for an internal Voice/Telephony TX sink
     * or active headset communication bus so MediaPlayer can route directly via setPreferredDevice().
     */
    fun findInternalVoiceTxSink(): AudioDeviceInfo? {
        return try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // Priority 1: Internal Telephony / Voice Uplink TX bus (if exposed by device Audio HAL)
            outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
                // Priority 2: Remote Submix / Internal Loopback bus
                ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX }
                // Priority 3: Active Bluetooth SCO Voice Call channel
                ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                // Priority 4: Active Wired / USB Headset channel
                ?: outputs.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
        } catch (_: Exception) {
            null
        }
    }

    fun restoreAudioState() {
        // No forced speakerphone or volume mutation to restore
    }
}
