package com.darkcore.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Starts the connection service after a device reboot if the user previously enabled it.
 *  This restores connectivity; Android still controls when camera/microphone capture may
 * start because those are while-in-use protected foreground-service capabilities.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("darkcore_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("connection_enabled", false)) return

        val serviceIntent = Intent(context, ConnectionService::class.java).apply {
            action = ConnectionService.ACTION_CONNECT
            putExtra(ConnectionService.EXTRA_HOST, prefs.getString("host", "").orEmpty())
        }
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
    }
}
