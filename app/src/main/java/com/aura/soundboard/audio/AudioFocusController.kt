package com.aura.soundboard.audio

import android.content.Context
import android.media.AudioManager

/**
 * Non-Intrusive Voice Call & Discord Routing Controller.
 * CRITICAL: Never calls audioManager.requestAudioFocus() when Voice Call / Discord mode is active,
 * because requesting audio focus causes Discord Android to mute/suspend its own microphone ('!' badge).
 */
class AudioFocusController(
    context: Context,
    @Suppress("UNUSED_PARAMETER") private val onFocusLostCallback: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousSpeakerState: Boolean? = null
    private var previousAlarmVol: Int? = null
    private var previousMusicVol: Int? = null

    fun prepareVoiceCallTransmission(voiceBoostEnabled: Boolean) {
        if (!voiceBoostEnabled) return
        try {
            if (previousSpeakerState == null) {
                previousSpeakerState = audioManager.isSpeakerphoneOn
            }
            // Ensure audio is not trapped in the tiny top earpiece during a Discord/voice call
            if (!audioManager.isWiredHeadsetOn && !audioManager.isBluetoothScoOn) {
                audioManager.isSpeakerphoneOn = true
            }
            // Boost alarm/sonification and media stream hardware volume so mic picks up clean signal
            if (previousAlarmVol == null) {
                previousAlarmVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
            }
            if (previousMusicVol == null) {
                previousMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetMusic = (maxMusic * 0.92f).toInt().coerceAtLeast(previousMusicVol ?: 1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)
            }
        } catch (_: Exception) {
        }
    }

    fun restoreAudioState() {
        try {
            previousSpeakerState?.let { prev ->
                audioManager.isSpeakerphoneOn = prev
            }
            previousAlarmVol?.let { prev ->
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prev, 0)
            }
            previousMusicVol?.let { prev ->
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0)
            }
        } catch (_: Exception) {
        } finally {
            previousSpeakerState = null
            previousAlarmVol = null
            previousMusicVol = null
        }
    }
}
