package com.aura.soundboard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class AudioFocusController(
    context: Context,
    private val onFocusLostCallback: () -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var previousSpeakerState: Boolean? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                onFocusLostCallback()
            }
        }
    }

    fun requestTransientFocus(micOverrideMode: Boolean): Boolean {
        try {
            if (micOverrideMode) {
                if (previousSpeakerState == null) {
                    previousSpeakerState = audioManager.isSpeakerphoneOn
                }
                if (!audioManager.isWiredHeadsetOn && !audioManager.isBluetoothScoOn && !audioManager.isBluetoothA2dpOn) {
                    audioManager.isSpeakerphoneOn = true
                }
            }
        } catch (_: Exception) {
        }

        if (hasFocus) return true
        return try {
            val focusType = if (micOverrideMode) {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            } else {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

                val req = AudioFocusRequest.Builder(focusType)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                focusRequest = req
                val res = audioManager.requestAudioFocus(req)
                hasFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                hasFocus
            } else {
                @Suppress("DEPRECATION")
                val res = audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    focusType
                )
                hasFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                hasFocus
            }
        } catch (_: Exception) {
            false
        }
    }

    fun abandonFocus() {
        try {
            previousSpeakerState?.let { prev ->
                audioManager.isSpeakerphoneOn = prev
            }
        } catch (_: Exception) {
        } finally {
            previousSpeakerState = null
        }

        if (!hasFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        } catch (_: Exception) {
        } finally {
            hasFocus = false
        }
    }
}
