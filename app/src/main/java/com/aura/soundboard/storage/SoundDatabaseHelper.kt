package com.aura.soundboard.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.aura.soundboard.models.SoundItem

class SoundDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SOUNDS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT NOT NULL,
                $COL_URI TEXT NOT NULL,
                $COL_CATEGORY TEXT NOT NULL DEFAULT 'SFX',
                $COL_VOLUME REAL NOT NULL DEFAULT 1.0,
                $COL_FAVORITE INTEGER NOT NULL DEFAULT 0,
                $COL_SORT_ORDER INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_PLAYED INTEGER NOT NULL DEFAULT 0,
                $COL_DURATION_MS INTEGER NOT NULL DEFAULT 0,
                $COL_FILE_SIZE INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_sounds_order ON $TABLE_SOUNDS($COL_SORT_ORDER ASC, $COL_ID ASC)")
        db.execSQL("CREATE INDEX idx_sounds_fav ON $TABLE_SOUNDS($COL_FAVORITE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SOUNDS")
        onCreate(db)
    }

    fun getAllSounds(): List<SoundItem> {
        val list = ArrayList<SoundItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SOUNDS,
            null,
            null,
            null,
            null,
            null,
            "$COL_SORT_ORDER ASC, $COL_ID ASC"
        )
        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(c.toSoundItem())
            }
        }
        return list
    }

    fun insertSound(item: SoundItem): Long {
        val db = writableDatabase
        val maxOrder = getMaxSortOrder(db)
        val values = ContentValues().apply {
            put(COL_NAME, item.name)
            put(COL_URI, item.uriString)
            put(COL_CATEGORY, item.category.ifBlank { "SFX" })
            put(COL_VOLUME, item.volume.coerceIn(0f, 1f))
            put(COL_FAVORITE, if (item.isFavorite) 1 else 0)
            put(COL_SORT_ORDER, maxOrder + 1)
            put(COL_LAST_PLAYED, item.lastPlayedAt)
            put(COL_DURATION_MS, item.durationMs)
            put(COL_FILE_SIZE, item.fileSizeBytes)
        }
        return db.insert(TABLE_SOUNDS, null, values)
    }

    fun updateSound(item: SoundItem) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_NAME, item.name)
            put(COL_CATEGORY, item.category.ifBlank { "SFX" })
            put(COL_VOLUME, item.volume.coerceIn(0f, 1f))
            put(COL_FAVORITE, if (item.isFavorite) 1 else 0)
            put(COL_SORT_ORDER, item.sortOrder)
            put(COL_LAST_PLAYED, item.lastPlayedAt)
        }
        db.update(TABLE_SOUNDS, values, "$COL_ID = ?", arrayOf(item.id.toString()))
    }

    fun updateLastPlayed(soundId: Long, timestamp: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_LAST_PLAYED, timestamp)
        }
        db.update(TABLE_SOUNDS, values, "$COL_ID = ?", arrayOf(soundId.toString()))
    }

    fun deleteSound(soundId: Long) {
        writableDatabase.delete(TABLE_SOUNDS, "$COL_ID = ?", arrayOf(soundId.toString()))
    }

    private fun getMaxSortOrder(db: SQLiteDatabase): Int {
        db.rawQuery("SELECT MAX($COL_SORT_ORDER) FROM $TABLE_SOUNDS", null).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    private fun Cursor.toSoundItem(): SoundItem {
        return SoundItem(
            id = getLong(getColumnIndexOrThrow(COL_ID)),
            name = getString(getColumnIndexOrThrow(COL_NAME)),
            uriString = getString(getColumnIndexOrThrow(COL_URI)),
            category = getString(getColumnIndexOrThrow(COL_CATEGORY)),
            volume = getFloat(getColumnIndexOrThrow(COL_VOLUME)),
            isFavorite = getInt(getColumnIndexOrThrow(COL_FAVORITE)) == 1,
            sortOrder = getInt(getColumnIndexOrThrow(COL_SORT_ORDER)),
            lastPlayedAt = getLong(getColumnIndexOrThrow(COL_LAST_PLAYED)),
            durationMs = getLong(getColumnIndexOrThrow(COL_DURATION_MS)),
            fileSizeBytes = getLong(getColumnIndexOrThrow(COL_FILE_SIZE))
        )
    }

    companion object {
        private const val DATABASE_NAME = "aura_soundboard.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_SOUNDS = "sounds"
        private const val COL_ID = "id"
        private const val COL_NAME = "name"
        private const val COL_URI = "uri_string"
        private const val COL_CATEGORY = "category"
        private const val COL_VOLUME = "volume"
        private const val COL_FAVORITE = "is_favorite"
        private const val COL_SORT_ORDER = "sort_order"
        private const val COL_LAST_PLAYED = "last_played_at"
        private const val COL_DURATION_MS = "duration_ms"
        private const val COL_FILE_SIZE = "file_size_bytes"
    }
}
