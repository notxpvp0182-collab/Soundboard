package com.aura.soundboard.models

data class SoundItem(
    val id: Long,
    val name: String,
    val uriString: String,
    val category: String = "SFX",
    val volume: Float = 1.0f,
    val isFavorite: Boolean = false,
    val sortOrder: Int = 0,
    val lastPlayedAt: Long = 0L,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long = 0L
) {
    val isShortClip: Boolean
        get() = durationMs in 1..5500L && fileSizeBytes in 1..(1536 * 1024L)
}
