package com.aura.soundboard.utils

import java.util.Locale

object AudioFileValidator {

    private val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "wav", "ogg", "oga", "m4a", "aac", "flac", "opus", "amr"
    )

    private val SUPPORTED_MIME_PREFIXES = listOf(
        "audio/",
        "application/ogg",
        "application/flac"
    )

    const val MAX_REASONABLE_FILE_SIZE_BYTES = 150L * 1024L * 1024L // 150 MB safety cap

    fun isValidMimeType(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        val normalized = mimeType.lowercase(Locale.ROOT)
        return SUPPORTED_MIME_PREFIXES.any { normalized.startsWith(it) }
    }

    fun isValidFileName(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in SUPPORTED_EXTENSIONS
    }

    fun isValidFileSize(sizeBytes: Long): Boolean {
        return sizeBytes in 64L..MAX_REASONABLE_FILE_SIZE_BYTES
    }

    fun sanitizeDisplayName(rawName: String?): String {
        if (rawName.isNullOrBlank()) return "Untitled Sound"
        val withoutExt = rawName.substringBeforeLast('.', rawName)
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
        return if (withoutExt.isEmpty()) "Sound Clip" else withoutExt.take(48)
    }
}
