package com.internetoptimizer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Boots the tunnel automatically after device reboot, if the user enabled
 * auto-start in settings.
 *
 * Declared in AndroidManifest.xml with:
 *   <receiver android:name=".app.BootReceiver"
 *             android:permission="android.permission.RECEIVE_BOOT_COMPLETED">
 *     <intent-filter android:priority="1000">
 *       <action android:name="android.intent.action.BOOT_COMPLETED" />
 *     </intent-filter>
 *   </receiver>
 *
 * Requires the RECEIVE_BOOT_COMPLETED permission in the manifest.
 *
 * Privacy note: This receiver does NOT send any data to external servers.
 * It only checks the local ConfigManager for the autoStart flag.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed — checking auto-start")
            val config = ConfigManager(context).loadConfig()
            if (config.autoStart) {
                Log.i(TAG, "Auto-start enabled — starting tunnel service")
                val serviceIntent = Intent(context, TunnelService::class.java).apply {
                    action = TunnelService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
