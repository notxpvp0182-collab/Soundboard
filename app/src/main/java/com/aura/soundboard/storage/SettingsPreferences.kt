package com.aura.soundboard.storage

import android.content.Context
import android.content.SharedPreferences
import com.aura.soundboard.models.AppSettings

class SettingsPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("aura_soundboard_prefs", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        return AppSettings(
            masterVolume = prefs.getFloat(KEY_MASTER_VOL, 0.90f).coerceIn(0f, 1f),
            hapticFeedback = prefs.getBoolean(KEY_HAPTIC, true),
            compactGrid = prefs.getBoolean(KEY_COMPACT_GRID, false),
            requestAudioFocus = prefs.getBoolean(KEY_AUDIO_FOCUS, true),
            voiceCommRouting = prefs.getBoolean(KEY_VOICE_COMM, false),
            allowOverlap = prefs.getBoolean(KEY_ALLOW_OVERLAP, true),
            stopOnExit = prefs.getBoolean(KEY_STOP_ON_EXIT, false),
            startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, false),
            overlayOpacity = prefs.getFloat(KEY_OVERLAY_OPACITY, 0.92f).coerceIn(0.35f, 1.0f),
            overlayShowLabels = prefs.getBoolean(KEY_OVERLAY_LABELS, true),
            reducedMotion = prefs.getBoolean(KEY_REDUCED_MOTION, false),
            overlayX = prefs.getInt(KEY_OVERLAY_X, 36),
            overlayY = prefs.getInt(KEY_OVERLAY_Y, 150),
            overlayWidthDp = prefs.getInt(KEY_OVERLAY_W, 270).coerceIn(180, 520),
            overlayHeightDp = prefs.getInt(KEY_OVERLAY_H, 300).coerceIn(170, 680),
            overlayMinimized = prefs.getBoolean(KEY_OVERLAY_MIN, false),
            onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putFloat(KEY_MASTER_VOL, settings.masterVolume.coerceIn(0f, 1f))
            .putBoolean(KEY_HAPTIC, settings.hapticFeedback)
            .putBoolean(KEY_COMPACT_GRID, settings.compactGrid)
            .putBoolean(KEY_AUDIO_FOCUS, settings.requestAudioFocus)
            .putBoolean(KEY_VOICE_COMM, settings.voiceCommRouting)
            .putBoolean(KEY_ALLOW_OVERLAP, settings.allowOverlap)
            .putBoolean(KEY_STOP_ON_EXIT, settings.stopOnExit)
            .putBoolean(KEY_START_ON_BOOT, settings.startOnBoot)
            .putFloat(KEY_OVERLAY_OPACITY, settings.overlayOpacity.coerceIn(0.35f, 1.0f))
            .putBoolean(KEY_OVERLAY_LABELS, settings.overlayShowLabels)
            .putBoolean(KEY_REDUCED_MOTION, settings.reducedMotion)
            .putInt(KEY_OVERLAY_X, settings.overlayX)
            .putInt(KEY_OVERLAY_Y, settings.overlayY)
            .putInt(KEY_OVERLAY_W, settings.overlayWidthDp)
            .putInt(KEY_OVERLAY_H, settings.overlayHeightDp)
            .putBoolean(KEY_OVERLAY_MIN, settings.overlayMinimized)
            .putBoolean(KEY_ONBOARDING_DONE, settings.onboardingCompleted)
            .apply()
    }

    companion object {
        private const val KEY_MASTER_VOL = "master_vol"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_COMPACT_GRID = "compact_grid"
        private const val KEY_AUDIO_FOCUS = "audio_focus"
        private const val KEY_VOICE_COMM = "voice_comm"
        private const val KEY_ALLOW_OVERLAP = "allow_overlap"
        private const val KEY_STOP_ON_EXIT = "stop_on_exit"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        private const val KEY_OVERLAY_LABELS = "overlay_labels"
        private const val KEY_REDUCED_MOTION = "reduced_motion"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_OVERLAY_W = "overlay_w"
        private const val KEY_OVERLAY_H = "overlay_h"
        private const val KEY_OVERLAY_MIN = "overlay_min"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
