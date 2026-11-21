package com.aura.soundboard.repository

import android.content.Context
import android.net.Uri
import com.aura.soundboard.models.SoundItem
import com.aura.soundboard.storage.SafAudioImporter
import com.aura.soundboard.storage.SoundDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

class SoundRepository(
    private val appContext: Context,
    private val dbHelper: SoundDatabaseHelper,
    private val importer: SafAudioImporter
) {
    private val _soundsFlow = MutableStateFlow<List<SoundItem>>(emptyList())
    val soundsFlow: StateFlow<List<SoundItem>> = _soundsFlow.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _soundsFlow.value = dbHelper.getAllSounds()
    }

    suspend fun importUri(uri: Uri): SafAudioImporter.ImportResult = withContext(Dispatchers.IO) {
        when (val result = importer.importFromUri(uri)) {
            is SafAudioImporter.ImportResult.Success -> {
                val id = dbHelper.insertSound(result.item)
                val saved = result.item.copy(id = id)
                _soundsFlow.value = dbHelper.getAllSounds()
                SafAudioImporter.ImportResult.Success(saved)
            }
            is SafAudioImporter.ImportResult.Error -> result
        }
    }

    suspend fun updateSound(item: SoundItem) = withContext(Dispatchers.IO) {
        dbHelper.updateSound(item)
        _soundsFlow.value = dbHelper.getAllSounds()
    }

    suspend fun markPlayed(soundId: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dbHelper.updateLastPlayed(soundId, now)
        _soundsFlow.value = _soundsFlow.value.map {
            if (it.id == soundId) it.copy(lastPlayedAt = now) else it
        }
    }

    suspend fun moveSoundOrder(soundId: Long, moveEarlier: Boolean) = withContext(Dispatchers.IO) {
        val currentList = dbHelper.getAllSounds().toMutableList()
        val index = currentList.indexOfFirst { it.id == soundId }
        if (index == -1) return@withContext
        val targetIndex = if (moveEarlier) index - 1 else index + 1
        if (targetIndex !in currentList.indices) return@withContext

        val temp = currentList[index]
        currentList[index] = currentList[targetIndex]
        currentList[targetIndex] = temp

        currentList.forEachIndexed { idx, soundItem ->
            dbHelper.updateSound(soundItem.copy(sortOrder = idx + 1))
        }
        _soundsFlow.value = dbHelper.getAllSounds()
    }

    suspend fun deleteSound(item: SoundItem) = withContext(Dispatchers.IO) {
        dbHelper.deleteSound(item.id)
        // Only delete physical file if it resides inside our app's private imported_sounds cache folder
        try {
            val uri = Uri.parse(item.uriString)
            if (uri.scheme == "file") {
                val file = File(uri.path ?: "")
                val managedDir = File(appContext.filesDir, "imported_sounds").canonicalPath
                if (file.exists() && file.canonicalPath.startsWith(managedDir)) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
        _soundsFlow.value = dbHelper.getAllSounds()
    }
}
