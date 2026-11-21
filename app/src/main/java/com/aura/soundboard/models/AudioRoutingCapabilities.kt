package com.aura.soundboard.models

data class AudioRoutingCapabilities(
    val sdkInt: Int,
    val supportsLowLatencyFeature: Boolean,
    val hasExternalHeadsetConnected: Boolean,
    val connectedOutputNames: List<String>,
    val routingSummary: String
)
