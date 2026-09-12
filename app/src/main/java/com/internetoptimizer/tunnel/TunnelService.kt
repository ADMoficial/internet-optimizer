package com.internetoptimizer.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.internetoptimizer.R
import com.internetoptimizer.app.ConfigManager
import com.internetoptimizer.app.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Foreground service that hosts the VpnService and runs the native tunnel.
 *
 * This service is started by [MainActivity] when the user toggles the
 * tunnel on. It:
 *
 * 1. Reads the VpnService (ensures [VpnServiceProvider] is populated).
 * 2. Establishes the TUN interface via [OptimizerVpnService.establishTunnel].
 * 3. Passes the TUN fd to the native Rust tunnel engine.
 * 4. Maintains a persistent notification (required for foreground services
 *    on Android 8+).
 *
 * The service runs on a background coroutine. When the system kills the
 * process, the tunnel is lost, but [BootReceiver] can restart it if
 * auto-start is enabled.
 */
class TunnelService : Service(), CoroutineScope {

    companion object {
        private const val TAG = "TunnelService"
        const val CHANNEL_ID = "tunnel_channel"
        const val NOTIFICATION_ID = 1

        // Intent actions
        const val ACTION_START = "com.internetoptimizer.action.START_TUNNEL"
        const val ACTION_STOP = "com.internetoptimizer.action.STOP_TUNNEL"
    }

    private val job = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.IO + job

    private val _tunnelState = MutableStateFlow(TunnelState.Stopped)
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    private var configManager: ConfigManager? = null
    private var tunnelManager: TunnelManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ${intent?.action}")

        // Initialize ConfigManager
        if (configManager == null) {
            configManager = ConfigManager(this)
        }

        when (intent?.action) {
            ACTION_START -> {
                // Ensure the VpnService is running so VpnServiceProvider is populated
                val vpnIntent = Intent(this, OptimizerVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(vpnIntent)
                }
                startTunnel(intent)
                startForeground(NOTIFICATION_ID, buildNotification(isRunning = true))
            }
            ACTION_STOP -> {
                stopTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY // Restart if killed by the system
    }

    private fun startTunnel(intent: Intent?) {
        val config = configManager?.loadConfig() ?: return

        // Wait for the VpnService to be registered (with timeout)
        launch {
            _tunnelState.value = TunnelState.Connecting

            // Poll for up to 5 seconds waiting for VpnServiceProvider to be set
            var vpnService: OptimizerVpnService? = VpnServiceProvider.get() as? OptimizerVpnService
            var attempts = 0
            while (vpnService == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                vpnService = VpnServiceProvider.get() as? OptimizerVpnService
                attempts++
            }

            if (vpnService == null) {
                Log.e(TAG, "VpnService not registered after timeout")
                _tunnelState.value = TunnelState.Stopped
                return@launch
            }

            // Extract config extras (if provided from MainActivity)
            val dnsEndpoint = intent?.getStringExtra("dns_endpoint") ?: config.dnsEndpoint
            val dnsUpstreamIp = intent?.getStringExtra("dns_upstream") ?: config.dnsUpstreamIp
            val excludedApps = intent?.getStringArrayListExtra("excluded_apps") ?: config.excludedApps

            tunnelManager = TunnelManager(vpnService)

            val result = tunnelManager?.startTunnel(
                dnsEndpoint = dnsEndpoint,
                dnsUpstreamIp = dnsUpstreamIp,
                excludedApps = excludedApps,
            )
            _tunnelState.value = if (result == true) {
                TunnelState.Running
            } else {
                TunnelState.Stopped
            }
        }
    }

    private fun stopTunnel() {
        tunnelManager?.stopTunnel()
        _tunnelState.value = TunnelState.Stopped
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTunnel()
        job.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description =
                getString(R.string.notification_channel_desc)
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isRunning: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Internet Optimizer")
            .setSmallIcon(R.drawable.ic_stat_name)

        if (isRunning) {
            builder.setContentText(getString(R.string.notification_content_running))
                .setOngoing(true)
                .addAction(
                    android.R.drawable.ic_delete,
                    getString(R.string.notification_action_stop),
                    Intent(ACTION_STOP).setClassName(
                        packageName, TunnelService::class.java.name
                    ),
                )
        } else {
            builder.setContentText("Pronto para otimizar")
                .setOngoing(false)
        }

        return builder.build()
    }
}
