package com.aura.soundboard.models

data class AppSettings(
    val masterVolume: Float = 0.90f,
    val hapticFeedback: Boolean = true,
    val compactGrid: Boolean = false,
    val requestAudioFocus: Boolean = true,
    val voiceCommRouting: Boolean = false,
    val allowOverlap: Boolean = true,
    val stopOnExit: Boolean = false,
    val startOnBoot: Boolean = false,
    val overlayOpacity: Float = 0.92f,
    val overlayShowLabels: Boolean = true,
    val reducedMotion: Boolean = false,
    val overlayX: Int = 40,
    val overlayY: Int = 160,
    val overlayWidthDp: Int = 270,
    val overlayHeightDp: Int = 310,
    val overlayMinimized: Boolean = false,
    val onboardingCompleted: Boolean = false
)
