package com.aura.soundboard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
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
 * Backend Internal Voice-Channel Routing + Soft Local Monitor Audio Engine:
 * 1. Never blasts the loudspeaker or alters system volume sliders.
 * 2. Routes primary communication stream to USAGE_VOICE_COMMUNICATION_SIGNALLING / internal HAL sink
 *    via setPreferredDevice() where supported by the Android hardware abstraction layer.
 * 3. Plays local monitor softly (attenuated by SOFT_MONITOR_FACTOR) so the user hears the clip
 *    gently without blasting the external speaker.
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

    private val routingController = AudioFocusController(context) {
        stopAll()
    }

    // Primary backend communication stream players
    private val activeTxPlayers = ConcurrentHashMap<Long, MediaPlayer>()
    // Soft local monitor players (when an internal telephony/submix sink is separate)
    private val activeMonitorPlayers = ConcurrentHashMap<Long, MediaPlayer>()
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

        activeItemVolumes[item.id] = item.volume.coerceIn(0f, 1f)
        scope.launch { soundRepository.markPlayed(item.id) }

        playInternalBackendAndSoftMonitor(item)
    }

    private fun playInternalBackendAndSoftMonitor(item: SoundItem) {
        scope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepository.current
                val uri = Uri.parse(item.uriString)
                val preferredTxSink = routingController.findInternalVoiceTxSink()

                val txPlayer = MediaPlayer()
                val usage = if (settings.voiceCommRouting) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING
                } else {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                }

                txPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                txPlayer.setDataSource(context, uri)

                // Route directly to internal HAL voice-tx or communication sink if present
                if (preferredTxSink != null) {
                    try {
                        txPlayer.setPreferredDevice(preferredTxSink)
                    } catch (_: Exception) {
                    }
                }

                // Keep local audible output soft and comfortable (never blasting at 100%)
                val rawVol = (item.volume * settings.masterVolume).coerceIn(0f, 1f)
                val softVol = (rawVol * SOFT_MONITOR_GAIN_FACTOR).coerceIn(0.05f, 0.55f)
                txPlayer.setVolume(softVol, softVol)

                txPlayer.setOnCompletionListener { player ->
                    cleanupPlayers(item.id, player)
                }
                txPlayer.setOnErrorListener { player, _, _ ->
                    cleanupPlayers(item.id, player)
                    true
                }
                txPlayer.prepare()
                activeTxPlayers[item.id] = txPlayer
                markSoundActive(item.id, true)
                txPlayer.start()
            } catch (_: Exception) {
                // Fallback to soft standard stream if device HAL rejects signalling usage
                playSoftFallbackMonitor(item)
            }
        }
    }

    private fun playSoftFallbackMonitor(item: SoundItem) {
        scope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepository.current
                val uri = Uri.parse(item.uriString)
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(context, uri)
                val rawVol = (item.volume * settings.masterVolume).coerceIn(0f, 1f)
                val softVol = (rawVol * SOFT_MONITOR_GAIN_FACTOR).coerceIn(0.05f, 0.50f)
                mp.setVolume(softVol, softVol)
                mp.setOnCompletionListener { player ->
                    cleanupPlayers(item.id, player)
                }
                mp.setOnErrorListener { player, _, _ ->
                    cleanupPlayers(item.id, player)
                    true
                }
                mp.prepare()
                activeTxPlayers[item.id] = mp
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
        activeTxPlayers.remove(soundId)?.let { player ->
            cleanupPlayers(soundId, player)
        }
        activeMonitorPlayers.remove(soundId)?.let { monitor ->
            try {
                if (monitor.isPlaying) monitor.stop()
                monitor.release()
            } catch (_: Exception) {
            }
        }
        markSoundActive(soundId, false)
    }

    override fun stopAll() {
        val players = activeTxPlayers.entries.toList()
        activeTxPlayers.clear()
        players.forEach { (id, player) ->
            cleanupPlayers(id, player)
        }
        val monitors = activeMonitorPlayers.entries.toList()
        activeMonitorPlayers.clear()
        monitors.forEach { (_, monitor) ->
            try {
                if (monitor.isPlaying) monitor.stop()
                monitor.release()
            } catch (_: Exception) {
            }
        }
        _activeSoundIds.value = emptySet()
        routingController.restoreAudioState()
    }

    override fun setMasterVolume(volume: Float) {
        val master = volume.coerceIn(0f, 1f)
        activeTxPlayers.forEach { (soundId, player) ->
            try {
                val itemVol = activeItemVolumes[soundId] ?: 1f
                val softVol = (itemVol * master * SOFT_MONITOR_GAIN_FACTOR).coerceIn(0.02f, 0.60f)
                player.setVolume(softVol, softVol)
            } catch (_: Exception) {
            }
        }
    }

    override fun updateItemVolume(soundId: Long, newVolume: Float) {
        val itemVol = newVolume.coerceIn(0f, 1f)
        activeItemVolumes[soundId] = itemVol
        val master = settingsRepository.current.masterVolume
        val softVol = (itemVol * master * SOFT_MONITOR_GAIN_FACTOR).coerceIn(0.02f, 0.60f)

        activeTxPlayers[soundId]?.let { player ->
            try {
                player.setVolume(softVol, softVol)
            } catch (_: Exception) {
            }
        }
    }

    override fun onTrimMemory() {}

    override fun release() {
        stopAll()
    }

    private fun cleanupPlayers(soundId: Long, player: MediaPlayer) {
        activeTxPlayers.remove(soundId)
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
    }

    companion object {
        // Keeps audible speaker monitor soft so the phone never blasts at full volume
        private const val SOFT_MONITOR_GAIN_FACTOR = 0.38f
    }
}
