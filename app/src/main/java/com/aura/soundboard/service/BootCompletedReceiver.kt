package com.aura.soundboard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aura.soundboard.permissions.PermissionManager
import com.aura.soundboard.storage.SettingsPreferences

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsPreferences(context).load()
        if (settings.startOnBoot && PermissionManager(context).canDrawOverlays()) {
            SoundboardForegroundService.start(context)
        }
    }
}
