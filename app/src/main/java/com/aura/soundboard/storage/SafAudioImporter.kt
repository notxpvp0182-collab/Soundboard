package com.aura.soundboard.storage

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.aura.soundboard.models.SoundItem
import com.aura.soundboard.utils.AudioFileValidator
import java.io.File
import java.io.FileOutputStream

class SafAudioImporter(private val context: Context) {

    sealed class ImportResult {
        data class Success(val item: SoundItem) : ImportResult()
        data class Error(val reason: String) : ImportResult()
    }

    fun importFromUri(uri: Uri): ImportResult {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)

        var displayName = "Imported Sound"
        var fileSize = 0L

        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            displayName = cursor.getString(nameIdx)
                        }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            fileSize = cursor.getLong(sizeIdx)
                        }
                    }
                }
        } catch (_: Exception) {
            return ImportResult.Error("Unable to read selected file metadata.")
        }

        val validMime = AudioFileValidator.isValidMimeType(mimeType)
        val validName = AudioFileValidator.isValidFileName(displayName)
        if (!validMime && !validName) {
            return ImportResult.Error("Unsupported audio format: $displayName")
        }

        if (fileSize > 0L && !AudioFileValidator.isValidFileSize(fileSize)) {
            return ImportResult.Error("File size is invalid or exceeds the 150 MB limit.")
        }

        // Attempt to take persistable URI permission first to avoid duplicating large files
        var persistedUriString = uri.toString()
        var permissionTaken = false
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            permissionTaken = true
        } catch (_: SecurityException) {
            permissionTaken = false
        }

        // If provider does not support persistable permission and file is reasonably small (<25MB), copy to internal storage
        if (!permissionTaken) {
            val copiedFile = copyToManagedStorage(uri, displayName)
                ?: return ImportResult.Error("Could not persist access to audio file.")
            persistedUriString = Uri.fromFile(copiedFile).toString()
            if (fileSize <= 0L) fileSize = copiedFile.length()
        }

        // Verify audio decodability and extract duration safely
        val durationMs = extractDurationSafely(Uri.parse(persistedUriString))
            ?: return ImportResult.Error("Corrupt or unreadable audio stream in $displayName.")

        val cleanTitle = AudioFileValidator.sanitizeDisplayName(displayName)
        val category = if (durationMs <= 6000L) "SFX" else "Music"

        return ImportResult.Success(
            SoundItem(
                id = 0L,
                name = cleanTitle,
                uriString = persistedUriString,
                category = category,
                volume = 1.0f,
                isFavorite = false,
                sortOrder = 0,
                lastPlayedAt = 0L,
                durationMs = durationMs,
                fileSizeBytes = fileSize
            )
        )
    }

    private fun extractDurationSafely(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            durStr?.toLongOrNull()?.coerceAtLeast(100L) ?: 1000L
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun copyToManagedStorage(sourceUri: Uri, originalName: String): File? {
        return try {
            val dir = File(context.filesDir, "imported_sounds").apply { mkdirs() }
            val ext = originalName.substringAfterLast('.', "mp3").take(5)
            val destFile = File(dir, "snd_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            } ?: return null
            if (destFile.length() < 64L) {
                destFile.delete()
                null
            } else {
                destFile
            }
        } catch (_: Exception) {
            null
        }
    }
}
