package com.aura.soundboard.di

import android.content.Context
import com.aura.soundboard.audio.AudioEngine
import com.aura.soundboard.audio.HybridAudioEngine
import com.aura.soundboard.audio.MicMonitorMixer
import com.aura.soundboard.overlay.OverlayController
import com.aura.soundboard.permissions.PermissionManager
import com.aura.soundboard.repository.SettingsRepository
import com.aura.soundboard.repository.SoundRepository
import com.aura.soundboard.storage.SafAudioImporter
import com.aura.soundboard.storage.SettingsPreferences
import com.aura.soundboard.storage.SoundDatabaseHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class AppContainer(appContext: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val permissionManager = PermissionManager(appContext)
    private val settingsPreferences = SettingsPreferences(appContext)
    val settingsRepository = SettingsRepository(settingsPreferences)

    private val dbHelper = SoundDatabaseHelper(appContext)
    private val safImporter = SafAudioImporter(appContext)
    val soundRepository = SoundRepository(appContext, dbHelper, safImporter)

    val audioEngine: AudioEngine = HybridAudioEngine(
        context = appContext,
        settingsRepository = settingsRepository,
        soundRepository = soundRepository,
        scope = appScope,
        onPlaybackError = { msg ->
            appScope.launch { _messages.emit(msg) }
        }
    )

    val micMonitorMixer = MicMonitorMixer(
        context = appContext,
        permissionManager = permissionManager,
        scope = appScope
    )

    val overlayController = OverlayController(
        context = appContext,
        permissionManager = permissionManager,
        settingsRepository = settingsRepository,
        soundRepository = soundRepository,
        audioEngine = audioEngine,
        scope = appScope
    )

    init {
        appScope.launch {
            soundRepository.refresh()
        }
    }

    fun emitMessage(text: String) {
        appScope.launch { _messages.emit(text) }
    }
}
