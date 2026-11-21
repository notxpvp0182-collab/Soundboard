package com.aura.soundboard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import com.aura.soundboard.models.SoundItem
import com.aura.soundboard.repository.SettingsRepository
import com.aura.soundboard.repository.SoundRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Mic-Override & Low-Latency Audio Engine:
 * - Never routes to USAGE_VOICE_COMMUNICATION earpiece (which Android's hardware AEC subtracts from the mic!).
 * - Routes via USAGE_MEDIA + Exclusive Transient Focus + Speaker Proximity Override + Hardware LoudnessEnhancer DSP boost
 *   so soundboard playback dominates the microphone pickup cleanly over user speech during calls/voice chats.
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

    private var soundPool: SoundPool? = null
    private val loadedPoolSamples = LinkedHashMap<Long, Int>(16, 0.75f, true)
    private val pendingPoolPlays = ConcurrentHashMap<Int, SoundItem>()
    private val activePoolStreams = ConcurrentHashMap<Long, Int>()
    private val poolCompletionJobs = ConcurrentHashMap<Long, Job>()

    private val activeMediaPlayers = ConcurrentHashMap<Long, MediaPlayer>()
    private val activeEnhancers = ConcurrentHashMap<Long, LoudnessEnhancer>()
    private val activeItemVolumes = ConcurrentHashMap<Long, Float>()

    init {
        initSoundPool()
    }

    private fun initSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_CONCURRENT_STREAMS)
            .setAudioAttributes(attrs)
            .build()
            .apply {
                setOnLoadCompleteListener { _, sampleId, status ->
                    val pendingItem = pendingPoolPlays.remove(sampleId)
                    if (status == 0 && pendingItem != null) {
                        triggerLoadedPoolSample(pendingItem, sampleId)
                    }
                }
            }
    }

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

        focusController.requestTransientFocus(settings.voiceCommRouting)
        activeItemVolumes[item.id] = item.volume.coerceIn(0f, 1f)
        scope.launch { soundRepository.markPlayed(item.id) }

        // In Mic-Override Boost mode, use MediaPlayer with hardware LoudnessEnhancer DSP boost
        if (!settings.voiceCommRouting && item.isShortClip) {
            playViaSoundPool(item)
        } else {
            playViaStreamingMediaPlayer(item)
        }
    }

    private fun playViaSoundPool(item: SoundItem) {
        val pool = soundPool ?: run {
            initSoundPool()
            soundPool
        } ?: return

        val existingSampleId = synchronized(loadedPoolSamples) { loadedPoolSamples[item.id] }
        if (existingSampleId != null) {
            triggerLoadedPoolSample(item, existingSampleId)
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(item.uriString)
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd == null) {
                    playViaStreamingMediaPlayer(item)
                    return@launch
                }
                pfd.use { descriptor ->
                    val sampleId = pool.load(descriptor.fileDescriptor, 0L, descriptor.statSize, 1)
                    if (sampleId > 0) {
                        synchronized(loadedPoolSamples) {
                            if (loadedPoolSamples.size >= MAX_CACHED_POOL_SAMPLES) {
                                val eldest = loadedPoolSamples.entries.iterator().next()
                                pool.unload(eldest.value)
                                loadedPoolSamples.remove(eldest.key)
                            }
                            loadedPoolSamples[item.id] = sampleId
                        }
                        pendingPoolPlays[sampleId] = item
                    } else {
                        playViaStreamingMediaPlayer(item)
                    }
                }
            } catch (_: Exception) {
                playViaStreamingMediaPlayer(item)
            }
        }
    }

    private fun triggerLoadedPoolSample(item: SoundItem, sampleId: Int) {
        val pool = soundPool ?: return
        val effectiveVol = (item.volume * settingsRepository.current.masterVolume).coerceIn(0f, 1f)
        val streamId = pool.play(sampleId, effectiveVol, effectiveVol, 1, 0, 1.0f)
        if (streamId != 0) {
            activePoolStreams[item.id] = streamId
            markSoundActive(item.id, true)
            poolCompletionJobs[item.id]?.cancel()
            val duration = item.durationMs.coerceIn(250L, 6000L)
            poolCompletionJobs[item.id] = scope.launch {
                delay(duration)
                activePoolStreams.remove(item.id)
                poolCompletionJobs.remove(item.id)
                markSoundActive(item.id, false)
            }
        }
    }

    private fun playViaStreamingMediaPlayer(item: SoundItem) {
        scope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepository.current
                val uri = Uri.parse(item.uriString)
                val mp = MediaPlayer()
                // Use USAGE_MEDIA so Android's Call Echo Canceller (AEC) does NOT subtract the sound from the microphone!
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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

                // Attach Hardware DSP LoudnessEnhancer in Mic Override mode to dominate mic pickup
                if (settings.voiceCommRouting) {
                    try {
                        val enhancer = LoudnessEnhancer(mp.audioSessionId)
                        val boostMb = (650 * effectiveVol).toInt().coerceIn(0, 700)
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
        poolCompletionJobs.remove(soundId)?.cancel()
        activePoolStreams.remove(soundId)?.let { streamId ->
            soundPool?.stop(streamId)
        }
        activeMediaPlayers.remove(soundId)?.let { player ->
            cleanupMediaPlayer(soundId, player)
        }
        markSoundActive(soundId, false)
    }

    override fun stopAll() {
        poolCompletionJobs.values.forEach { it.cancel() }
        poolCompletionJobs.clear()

        activePoolStreams.values.forEach { streamId ->
            try {
                soundPool?.stop(streamId)
            } catch (_: Exception) {
            }
        }
        activePoolStreams.clear()

        val players = activeMediaPlayers.entries.toList()
        activeMediaPlayers.clear()
        players.forEach { (id, player) ->
            cleanupMediaPlayer(id, player)
        }

        _activeSoundIds.value = emptySet()
        focusController.abandonFocus()
    }

    override fun setMasterVolume(volume: Float) {
        val master = volume.coerceIn(0f, 1f)
        activePoolStreams.forEach { (soundId, streamId) ->
            val itemVol = activeItemVolumes[soundId] ?: 1f
            val eff = (itemVol * master).coerceIn(0f, 1f)
            soundPool?.setVolume(streamId, eff, eff)
        }
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

        activePoolStreams[soundId]?.let { streamId ->
            soundPool?.setVolume(streamId, eff, eff)
        }
        activeMediaPlayers[soundId]?.let { player ->
            try {
                player.setVolume(eff, eff)
            } catch (_: Exception) {
            }
        }
    }

    override fun onTrimMemory() {
        synchronized(loadedPoolSamples) {
            val pool = soundPool ?: return
            val iterator = loadedPoolSamples.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!_activeSoundIds.value.contains(entry.key)) {
                    pool.unload(entry.value)
                    iterator.remove()
                }
            }
        }
    }

    override fun release() {
        stopAll()
        synchronized(loadedPoolSamples) {
            loadedPoolSamples.clear()
        }
        try {
            soundPool?.release()
        } catch (_: Exception) {
        }
        soundPool = null
    }

    private fun cleanupMediaPlayer(soundId: Long, player: MediaPlayer) {
        activeMediaPlayers.remove(soundId)
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
            focusController.abandonFocus()
        }
    }

    companion object {
        private const val MAX_CONCURRENT_STREAMS = 6
        private const val MAX_CACHED_POOL_SAMPLES = 16
    }
}
