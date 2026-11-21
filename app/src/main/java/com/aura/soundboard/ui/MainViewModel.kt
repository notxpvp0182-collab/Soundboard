package com.aura.soundboard.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aura.soundboard.di.AppContainer
import com.aura.soundboard.models.SoundItem
import com.aura.soundboard.storage.SafAudioImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedFilter = MutableStateFlow(FILTER_ALL)

    val currentFilter: StateFlow<String> = selectedFilter
    val settingsFlow = container.settingsRepository.settingsFlow
    val activeSoundIds = container.audioEngine.activeSoundIds
    val isOverlayShowing = container.overlayController.isShowing
    val isMicMonitoring = container.micMonitorMixer.isMonitoring

    val filteredSounds: StateFlow<List<SoundItem>> = combine(
        container.soundRepository.soundsFlow,
        searchQuery,
        selectedFilter
    ) { allSounds, query, filter ->
        val categoryFiltered = when (filter) {
            FILTER_FAVORITES -> allSounds.filter { it.isFavorite }
            FILTER_RECENT -> allSounds.filter { it.lastPlayedAt > 0L }
                .sortedByDescending { it.lastPlayedAt }
            FILTER_SFX -> allSounds.filter { it.category.equals("SFX", ignoreCase = true) }
            FILTER_VOICE -> allSounds.filter { it.category.equals("Voice", ignoreCase = true) }
            FILTER_MUSIC -> allSounds.filter { it.category.equals("Music", ignoreCase = true) }
            else -> allSounds
        }

        if (query.isBlank()) {
            categoryFiltered
        } else {
            val q = query.trim().lowercase()
            categoryFiltered.filter {
                it.name.lowercase().contains(q) || it.category.lowercase().contains(q)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setCategoryFilter(filter: String) {
        selectedFilter.value = filter
    }

    fun importAudioUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var importedCount = 0
            var lastError: String? = null
            for (uri in uris) {
                when (val res = container.soundRepository.importUri(uri)) {
                    is SafAudioImporter.ImportResult.Success -> importedCount++
                    is SafAudioImporter.ImportResult.Error -> lastError = res.reason
                }
            }
            if (importedCount > 0) {
                container.emitMessage("Imported $importedCount sound(s) successfully.")
            } else if (lastError != null) {
                container.emitMessage(lastError)
            }
        }
    }

    fun togglePlay(item: SoundItem) {
        container.audioEngine.togglePlay(item)
    }

    fun stopAllSounds() {
        container.audioEngine.stopAll()
    }

    fun toggleFavorite(item: SoundItem) {
        viewModelScope.launch {
            container.soundRepository.updateSound(item.copy(isFavorite = !item.isFavorite))
        }
    }

    fun updateItemVolume(item: SoundItem, newVolume: Float) {
        val clamped = newVolume.coerceIn(0f, 1f)
        container.audioEngine.updateItemVolume(item.id, clamped)
        viewModelScope.launch {
            container.soundRepository.updateSound(item.copy(volume = clamped))
        }
    }

    fun saveEditedSound(updated: SoundItem) {
        container.audioEngine.updateItemVolume(updated.id, updated.volume)
        viewModelScope.launch {
            container.soundRepository.updateSound(updated)
            container.emitMessage("Saved \"${updated.name}\"")
        }
    }

    fun moveSoundOrder(soundId: Long, moveEarlier: Boolean) {
        viewModelScope.launch {
            container.soundRepository.moveSoundOrder(soundId, moveEarlier)
        }
    }

    fun deleteSound(item: SoundItem) {
        container.audioEngine.stopSound(item.id)
        viewModelScope.launch {
            container.soundRepository.deleteSound(item)
            container.emitMessage("Deleted \"${item.name}\"")
        }
    }

    fun setMasterVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        container.audioEngine.setMasterVolume(clamped)
        container.micMonitorMixer.setGain(clamped)
        container.settingsRepository.update { it.copy(masterVolume = clamped) }
    }

    companion object {
        const val FILTER_ALL = "ALL"
        const val FILTER_FAVORITES = "FAVORITES"
        const val FILTER_RECENT = "RECENT"
        const val FILTER_SFX = "SFX"
        const val FILTER_VOICE = "VOICE"
        const val FILTER_MUSIC = "MUSIC"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(container) as T
                }
            }
    }
}
