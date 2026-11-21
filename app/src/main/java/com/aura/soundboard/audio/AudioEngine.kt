package com.aura.soundboard.audio

import com.aura.soundboard.models.SoundItem
import kotlinx.coroutines.flow.StateFlow

interface AudioEngine {
    val activeSoundIds: StateFlow<Set<Long>>
    fun togglePlay(item: SoundItem)
    fun playSound(item: SoundItem)
    fun stopSound(soundId: Long)
    fun stopAll()
    fun setMasterVolume(volume: Float)
    fun updateItemVolume(soundId: Long, newVolume: Float)
    fun onTrimMemory()
    fun release()
}
