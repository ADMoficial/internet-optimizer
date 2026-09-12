package com.internetoptimizer.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.internetoptimizer.app.ConfigManager
import com.internetoptimizer.network.NetworkClient
import com.internetoptimizer.network.SpeedTest
import com.internetoptimizer.tunnel.NativeTunnel
import com.internetoptimizer.tunnel.OptimizerVpnService
import com.internetoptimizer.tunnel.TunnelService
import com.internetoptimizer.ui.MainScreen
import com.internetoptimizer.ui.MainUiState
import com.internetoptimizer.ui.MainViewModel
import com.internetoptimizer.ui.SettingsScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main activity — single-screen app with a toggle to start/stop the tunnel.
 *
 * The activity:
 * 1. Loads the saved config from ConfigManager.
 * 2. Requests VPN permission when the user taps the toggle for the first time.
 * 3. Observes the MainViewModel state for UI updates.
 * 4. Switches between MainScreen and SettingsScreen based on navigation state.
 */
class MainActivity : ComponentActivity() {

    private lateinit var configManager: ConfigManager
    private lateinit var viewModel: MainViewModel
    private lateinit var networkClient: NetworkClient
    private lateinit var speedTest: SpeedTest

    // VPN permission launcher — uses the system's VpnService prepare intent.
    // When the user taps "Ativar", we call VpnService.prepare() which shows
    // the system dialog asking for permission to create a VPN.
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // After the user grants permission (or cancels), check if we can proceed
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            // User denied — guide them to settings
            Toast.makeText(this, "Permissão de VPN necessária", Toast.LENGTH_LONG).show()
        } else {
            // Permission granted — start the tunnel
            startTunnelService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize dependencies
        configManager = ConfigManager(this)
        networkClient = NetworkClient(this)
        speedTest = SpeedTest(networkClient)
        viewModel = MainViewModel(configManager)

        // Load native library early (needed for tunnel)
        NativeTunnel.loadLibrary()

        // Observe UI state and render the appropriate screen
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { uiState ->
                val cfg = configManager.loadConfig()
                setContent {
                    if (uiState.showSettings) {
                        SettingsScreen(
                            config = cfg,
                            onConfigChange = { newConfig ->
                                configManager.saveConfig(newConfig)
                                viewModel.loadConfig()
                            },
                            onNavigateBack = { viewModel.refreshConfig() },
                            onExcludedAppsClick = { openAppPicker() },
                        )
                    } else {
                        MainScreen(
                            uiState = uiState,
                            onToggleTunnel = { handleToggleTunnel(uiState.isTunnelActive) },
                            onSettingsClick = { showSettings() },
                            onSpeedTestClick = { runSpeedTest() },
                            onExcludedAppsClick = { openAppPicker() },
                        )
                    }
                }
            }
        }

        // Initial config load
        viewModel.loadConfig()
    }

    private fun handleToggleTunnel(isCurrentlyActive: Boolean) {
        if (isCurrentlyActive) {
            stopTunnelService()
        } else {
            // Request VPN permission first
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                startTunnelService()
            }
        }
    }

    private fun startTunnelService() {
        val config = configManager.loadConfig()
        val serviceIntent = Intent(this, TunnelService::class.java).apply {
            action = TunnelService.ACTION_START
            putExtra("dns_endpoint", config.dnsEndpoint)
            putExtra("dns_upstream", config.dnsUpstreamIp)
            putStringArrayListExtra("excluded_apps", ArrayList(config.excludedApps))
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Otimização ativada", Toast.LENGTH_SHORT).show()
    }

    private fun stopTunnelService() {
        val serviceIntent = Intent(this, TunnelService::class.java).apply {
            action = TunnelService.ACTION_STOP
        }
        startService(serviceIntent)
        Toast.makeText(this, "Otimização desativada", Toast.LENGTH_SHORT).show()
    }

    private fun openAppPicker() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun showSettings() {
        viewModel.refreshConfig()
    }

    private fun runSpeedTest() {
        lifecycleScope.launch {
            val beforeResult = speedTest.runBenchmark()
            val afterResult = speedTest.runBenchmark()
            val comparison = speedTest.compareResults(beforeResult, afterResult)
            Toast.makeText(
                this@MainActivity,
                "Latência: ${comparison.latency.beforeMs}ms → ${comparison.latency.afterMs}ms",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
