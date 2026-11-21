package com.aura.soundboard.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import com.aura.soundboard.models.AudioRoutingCapabilities
import com.aura.soundboard.permissions.PermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Honest Microphone Capture + Local Headphone Monitor.
 * Standard Android does not allow a 3rd-party unrooted app to inject digital PCM audio
 * directly into another app's microphone input stream.
 *
 * This mixer provides:
 * 1. Device audio routing diagnostics (headset detection, low-latency support).
 * 2. Real-time local microphone + soundboard headphone monitoring (useful for hardware
 *    TRRS splitter loopback or live voice monitoring).
 * 3. Acoustic feedback protection when no headset is connected.
 */
class MicMonitorMixer(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val scope: CoroutineScope
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private var monitorJob: Job? = null
    private var masterGain: Float = 0.85f

    fun inspectCapabilities(): AudioRoutingCapabilities {
        val lowLatency = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val externalOutputs = mutableListOf<String>()
        var hasHeadset = false

        for (device in outputs) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    hasHeadset = true
                    externalOutputs.add(deviceTypeLabel(device.type))
                }
            }
        }

        val summary = buildString {
            append("Android API ${Build.VERSION.SDK_INT} Audio Security Policy:\n\n")
            append("1. Inter-App Mic Isolation: Unrooted Android isolates app microphone streams. ")
            append("Third-party apps cannot silently overwrite Discord/WhatsApp/Game mic inputs in software.\n\n")
            append("2. What Works Natively:\n")
            append("• Floating Overlay over any app or game.\n")
            append("• Acoustic Speaker-to-Mic pickup when playing on speakerphone.\n")
            append("• Hardware TRRS Splitter / AUX Loopback with Local Mic + Soundboard Monitoring.\n\n")
            append("3. Current Hardware State:\n")
            append("• Low-Latency Audio Track: ${if (lowLatency) "Supported" else "Standard"}\n")
            append("• External Headset/Earphones: ${if (hasHeadset) externalOutputs.distinct().joinToString() else "None detected (Speaker active)"}")
        }

        return AudioRoutingCapabilities(
            sdkInt = Build.VERSION.SDK_INT,
            supportsLowLatencyFeature = lowLatency,
            hasExternalHeadsetConnected = hasHeadset,
            connectedOutputNames = externalOutputs.distinct(),
            routingSummary = summary
        )
    }

    fun setGain(gain: Float) {
        masterGain = gain.coerceIn(0f, 1f)
    }

    @SuppressLint("MissingPermission")
    fun startMonitoring(allowWithoutHeadset: Boolean = false): Result<Unit> {
        if (_isMonitoring.value) return Result.success(Unit)
        if (!permissionManager.hasRecordAudioPermission()) {
            return Result.failure(SecurityException("Microphone permission is required for live monitoring."))
        }

        val caps = inspectCapabilities()
        if (!caps.hasExternalHeadsetConnected && !allowWithoutHeadset) {
            return Result.failure(
                IllegalStateException(
                    "Headphones not detected. Plug in wired/Bluetooth earphones to avoid speaker echo feedback, or confirm speaker monitoring."
                )
            )
        }

        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1024)

        monitorJob = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            var player: AudioTrack? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    _isMonitoring.value = false
                    return@launch
                }

                player = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()

                val buffer = ShortArray(minBuffer / 2)
                recorder.startRecording()
                player.play()
                _isMonitoring.value = true

                while (isActive && _isMonitoring.value) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val g = masterGain
                        for (i in 0 until read) {
                            val scaled = (buffer[i] * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            buffer[i] = scaled.toShort()
                        }
                        player.write(buffer, 0, read)
                    }
                }
            } catch (_: Exception) {
            } finally {
                _isMonitoring.value = false
                try {
                    recorder?.stop()
                } catch (_: Exception) {
                }
                try {
                    recorder?.release()
                } catch (_: Exception) {
                }
                try {
                    player?.stop()
                } catch (_: Exception) {
                }
                try {
                    player?.release()
                } catch (_: Exception) {
                }
            }
        }
        return Result.success(Unit)
    }

    fun stopMonitoring() {
        _isMonitoring.value = false
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Call Headset"
        else -> "External Audio"
    }
}
