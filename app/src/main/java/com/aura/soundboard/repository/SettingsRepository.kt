package com.aura.soundboard.repository

import com.aura.soundboard.models.AppSettings
import com.aura.soundboard.storage.SettingsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(private val preferences: SettingsPreferences) {

    private val _settingsFlow = MutableStateFlow(preferences.load())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    val current: AppSettings
        get() = _settingsFlow.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settingsFlow.value)
        _settingsFlow.value = updated
        preferences.save(updated)
    }
}
