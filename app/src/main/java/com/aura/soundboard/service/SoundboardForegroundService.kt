package com.aura.soundboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aura.soundboard.R
import com.aura.soundboard.SoundboardApp
import com.aura.soundboard.ui.MainActivity

class SoundboardForegroundService : Service() {

    private val container by lazy { (application as SoundboardApp).container }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL_SOUNDS -> {
                container.audioEngine.stopAll()
            }
            ACTION_TOGGLE_OVERLAY -> {
                container.overlayController.toggleOverlay()
            }
            ACTION_SHUTDOWN_SERVICE -> {
                container.overlayController.hideOverlay()
                container.audioEngine.stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
            }
        }
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.audioEngine.onTrimMemory()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            // Avoid ForegroundServiceStartNotAllowedException crash on restricted background starts
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleOverlayIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, SoundboardForegroundService::class.java).apply {
                action = ACTION_TOGGLE_OVERLAY
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopSoundsIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, SoundboardForegroundService::class.java).apply {
                action = ACTION_STOP_ALL_SOUNDS
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val exitIntent = PendingIntent.getService(
            this,
            103,
            Intent(this, SoundboardForegroundService::class.java).apply {
                action = ACTION_SHUTDOWN_SERVICE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sound_wave)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Overlay & low-latency sound engine ready")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Overlay", toggleOverlayIntent)
            .addAction(0, "Stop All", stopSoundsIntent)
            .addAction(0, "Exit", exitIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "aura_soundboard_engine"
        private const val NOTIFICATION_ID = 4041

        const val ACTION_START = "com.aura.soundboard.action.START"
        const val ACTION_TOGGLE_OVERLAY = "com.aura.soundboard.action.TOGGLE_OVERLAY"
        const val ACTION_STOP_ALL_SOUNDS = "com.aura.soundboard.action.STOP_ALL_SOUNDS"
        const val ACTION_SHUTDOWN_SERVICE = "com.aura.soundboard.action.SHUTDOWN"

        fun start(context: Context) {
            val intent = Intent(context, SoundboardForegroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }
    }
}
