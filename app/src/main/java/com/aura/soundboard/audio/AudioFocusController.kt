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

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                onFocusLostCallback()
            }
        }
    }

    fun requestTransientFocus(voiceCommMode: Boolean): Boolean {
        if (hasFocus) return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(
                        if (voiceCommMode) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(
                        if (voiceCommMode) AudioAttributes.CONTENT_TYPE_SPEECH
                        else AudioAttributes.CONTENT_TYPE_SONIFICATION
                    )
                    .build()

                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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
                    if (voiceCommMode) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                hasFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                hasFocus
            }
        } catch (_: Exception) {
            false
        }
    }

    fun abandonFocus() {
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
