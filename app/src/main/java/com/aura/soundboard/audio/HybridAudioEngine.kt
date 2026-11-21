package com.aura.soundboard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import com.aura.soundboard.models.SoundItem
import com.aura.soundboard.repository.SettingsRepository
import com.aura.soundboard.repository.SoundRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Discord & Voice-Chat Compatible Audio Engine:
 * 1. NEVER steals AudioFocus (keeps Discord's microphone open without the '!' mute icon).
 * 2. Uses USAGE_ALARM / CONTENT_TYPE_SONIFICATION in Voice Boost mode so Android's MODE_IN_COMMUNICATION
 *     neither ducks the stream nor feeds it into the primary media AEC echo-subtraction reference.
 * 3. Applies Vocal Formant Equalizer Boost (300Hz - 3400Hz) + LoudnessEnhancer (+900mB) so Discord's
 *    Voice Activity Detector (VAD) triggers the green speaking ring reliably.
 */
class HybridAudioEngine(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val soundRepository: SoundRepository,
    private val scope: CoroutineScope,
    private val onPlaybackError: (String) -> Unit
) : AudioEngine {

    private val _activeSoundIds = MutableStateFlow<Set<Long>>(emptySet())
    override val activeSoundIds: StateFlow<Set<Long>> = _activeSoundIds.asStateFlow()

    private val focusController = AudioFocusController(context) {
        stopAll()
    }

    private val activeMediaPlayers = ConcurrentHashMap<Long, MediaPlayer>()
    private val activeEnhancers = ConcurrentHashMap<Long, LoudnessEnhancer>()
    private val activeEqualizers = ConcurrentHashMap<Long, Equalizer>()
    private val activeItemVolumes = ConcurrentHashMap<Long, Float>()

    override fun togglePlay(item: SoundItem) {
        if (_activeSoundIds.value.contains(item.id)) {
            stopSound(item.id)
        } else {
            playSound(item)
        }
    }

    override fun playSound(item: SoundItem) {
        val settings = settingsRepository.current
        if (!settings.allowOverlap) {
            stopAll()
        } else if (_activeSoundIds.value.contains(item.id)) {
            stopSound(item.id)
        }

        // Prepare speakerphone & stream boost WITHOUT stealing Discord's audio focus
        focusController.prepareVoiceCallTransmission(settings.voiceCommRouting)
        activeItemVolumes[item.id] = item.volume.coerceIn(0f, 1f)
        scope.launch { soundRepository.markPlayed(item.id) }

        playViaVoiceBoostedPlayer(item)
    }

    private fun playViaVoiceBoostedPlayer(item: SoundItem) {
        scope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepository.current
                val uri = Uri.parse(item.uriString)
                val mp = MediaPlayer()

                // USAGE_ALARM prevents Android from ducking volume during Discord MODE_IN_COMMUNICATION calls
                val usage = if (settings.voiceCommRouting) {
                    AudioAttributes.USAGE_ALARM
                } else {
                    AudioAttributes.USAGE_MEDIA
                }
                val contentType = if (settings.voiceCommRouting) {
                    AudioAttributes.CONTENT_TYPE_SONIFICATION
                } else {
                    AudioAttributes.CONTENT_TYPE_MUSIC
                }

                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(contentType)
                        .build()
                )
                mp.setDataSource(context, uri)
                val effectiveVol = (item.volume * settings.masterVolume).coerceIn(0f, 1f)
                mp.setVolume(effectiveVol, effectiveVol)
                mp.setOnCompletionListener { player ->
                    cleanupMediaPlayer(item.id, player)
                }
                mp.setOnErrorListener { player, _, _ ->
                    cleanupMediaPlayer(item.id, player)
                    true
                }
                mp.prepare()

                if (settings.voiceCommRouting) {
                    // 1. Boost human vocal frequencies (300Hz - 3400Hz) so Discord VAD lights up green ring
                    try {
                        val eq = Equalizer(0, mp.audioSessionId)
                        val bands = eq.numberOfBands
                        val maxLevel = eq.bandLevelRange[1]
                        for (b in 0 until bands) {
                            val bandIndex = b.toShort()
                            val centerFreqMilliHz = eq.getCenterFreq(bandIndex)
                            if (centerFreqMilliHz in 250_000..3_800_000) {
                                eq.setBandLevel(bandIndex, maxLevel)
                            }
                        }
                        eq.enabled = true
                        activeEqualizers[item.id] = eq
                    } catch (_: Exception) {
                    }

                    // 2. Apply Hardware DSP LoudnessEnhancer (+900mB)
                    try {
                        val enhancer = LoudnessEnhancer(mp.audioSessionId)
                        val boostMb = (900 * effectiveVol).toInt().coerceIn(0, 1000)
                        enhancer.setTargetGain(boostMb)
                        enhancer.enabled = true
                        activeEnhancers[item.id] = enhancer
                    } catch (_: Exception) {
                    }
                }

                activeMediaPlayers[item.id] = mp
                markSoundActive(item.id, true)
                mp.start()
            } catch (_: Exception) {
                markSoundActive(item.id, false)
                scope.launch(Dispatchers.Main) {
                    onPlaybackError("UNABLE TO PLAY ${item.name}")
                }
            }
        }
    }

    override fun stopSound(soundId: Long) {
        activeMediaPlayers.remove(soundId)?.let { player ->
            cleanupMediaPlayer(soundId, player)
        }
        markSoundActive(soundId, false)
    }

    override fun stopAll() {
        val players = activeMediaPlayers.entries.toList()
        activeMediaPlayers.clear()
        players.forEach { (id, player) ->
            cleanupMediaPlayer(id, player)
        }
        _activeSoundIds.value = emptySet()
        focusController.restoreAudioState()
    }

    override fun setMasterVolume(volume: Float) {
        val master = volume.coerceIn(0f, 1f)
        activeMediaPlayers.forEach { (soundId, player) ->
            try {
                val itemVol = activeItemVolumes[soundId] ?: 1f
                val eff = (itemVol * master).coerceIn(0f, 1f)
                player.setVolume(eff, eff)
            } catch (_: Exception) {
            }
        }
    }

    override fun updateItemVolume(soundId: Long, newVolume: Float) {
        val itemVol = newVolume.coerceIn(0f, 1f)
        activeItemVolumes[soundId] = itemVol
        val master = settingsRepository.current.masterVolume
        val eff = (itemVol * master).coerceIn(0f, 1f)

        activeMediaPlayers[soundId]?.let { player ->
            try {
                player.setVolume(eff, eff)
            } catch (_: Exception) {
            }
        }
    }

    override fun onTrimMemory() {}

    override fun release() {
        stopAll()
    }

    private fun cleanupMediaPlayer(soundId: Long, player: MediaPlayer) {
        activeMediaPlayers.remove(soundId)
        activeEqualizers.remove(soundId)?.let { eq ->
            try {
                eq.release()
            } catch (_: Exception) {
            }
        }
        activeEnhancers.remove(soundId)?.let { enhancer ->
            try {
                enhancer.release()
            } catch (_: Exception) {
            }
        }
        try {
            if (player.isPlaying) player.stop()
        } catch (_: Exception) {
        }
        try {
            player.release()
        } catch (_: Exception) {
        }
        markSoundActive(soundId, false)
    }

    private fun markSoundActive(soundId: Long, active: Boolean) {
        val current = _activeSoundIds.value.toMutableSet()
        if (active) current.add(soundId) else current.remove(soundId)
        _activeSoundIds.value = current
        if (current.isEmpty()) {
            focusController.restoreAudioState()
        }
    }
}
