package com.aura.soundboard.models

data class AppSettings(
    val masterVolume: Float = 0.90f,
    val hapticFeedback: Boolean = true,
    val compactGrid: Boolean = false,
    val requestAudioFocus: Boolean = true,
    val voiceCommRouting: Boolean = true,
    val allowOverlap: Boolean = true,
    val stopOnExit: Boolean = false,
    val startOnBoot: Boolean = false,
    val overlayOpacity: Float = 0.94f,
    val overlayShowLabels: Boolean = true,
    val reducedMotion: Boolean = false,
    val overlayX: Int = 24,
    val overlayY: Int = 180,
    val overlayWidthDp: Int = 280,
    val overlayHeightDp: Int = 320,
    val overlayBubbleSizeDp: Int = 58,
    val overlayMinimized: Boolean = true,
    val onboardingCompleted: Boolean = true
)
