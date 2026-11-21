package com.aura.soundboard

import com.aura.soundboard.models.AppSettings
import com.aura.soundboard.models.SoundItem
import com.aura.soundboard.utils.AudioFileValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioValidatorAndModelTest {

    @Test
    fun validator_acceptsSupportedAudioMimeTypes() {
        assertTrue(AudioFileValidator.isValidMimeType("audio/mpeg"))
        assertTrue(AudioFileValidator.isValidMimeType("audio/wav"))
        assertTrue(AudioFileValidator.isValidMimeType("audio/ogg"))
        assertTrue(AudioFileValidator.isValidMimeType("application/ogg"))
        assertFalse(AudioFileValidator.isValidMimeType("video/mp4"))
        assertFalse(AudioFileValidator.isValidMimeType("application/x-msdownload"))
    }

    @Test
    fun validator_acceptsSupportedFileExtensions() {
        assertTrue(AudioFileValidator.isValidFileName("airhorn_sfx.mp3"))
        assertTrue(AudioFileValidator.isValidFileName("voice_clip.WAV"))
        assertTrue(AudioFileValidator.isValidFileName("ambient.flac"))
        assertFalse(AudioFileValidator.isValidFileName("malware.apk"))
        assertFalse(AudioFileValidator.isValidFileName("script.sh"))
    }

    @Test
    fun validator_enforcesFileSizeBounds() {
        assertFalse(AudioFileValidator.isValidFileSize(0L))
        assertFalse(AudioFileValidator.isValidFileSize(12L))
        assertTrue(AudioFileValidator.isValidFileSize(128 * 1024L))
        assertFalse(AudioFileValidator.isValidFileSize(500L * 1024L * 1024L))
    }

    @Test
    fun validator_sanitizesDisplayName() {
        assertEquals("Epic Horn 01", AudioFileValidator.sanitizeDisplayName("Epic_Horn-01.mp3"))
        assertEquals("Untitled Sound", AudioFileValidator.sanitizeDisplayName("   "))
    }

    @Test
    fun soundItem_routesShortClipsToSoundPoolAndLongClipsToStreaming() {
        val shortClip = SoundItem(
            id = 1L,
            name = "Button Click",
            uriString = "content://media/external/audio/1",
            durationMs = 1800L,
            fileSizeBytes = 64 * 1024L
        )
        val longTrack = SoundItem(
            id = 2L,
            name = "Background Song",
            uriString = "content://media/external/audio/2",
            durationMs = 45_000L,
            fileSizeBytes = 4 * 1024 * 1024L
        )

        assertTrue(shortClip.isShortClip)
        assertFalse(longTrack.isShortClip)
    }

    @Test
    fun appSettings_hasSafeLowEndDeviceDefaults() {
        val defaults = AppSettings()
        assertEquals(0.90f, defaults.masterVolume, 0.001f)
        assertTrue(defaults.allowOverlap)
        assertFalse(defaults.overlayMinimized)
    }
}
