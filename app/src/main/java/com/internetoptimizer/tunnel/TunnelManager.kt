package com.internetoptimizer.tunnel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages the lifecycle of the local VPN tunnel.
 *
 * This class orchestrates the VpnService TUN creation and the native
 * Rust tunnel engine. It exposes tunnel state as LiveData so the UI
 * can react to on/off changes.
 *
 * Key responsibilities:
 * - Ask [OptimizerVpnService] to establish the TUN interface.
 * - Initialize and run the native tunnel engine (Rust).
 * - Manage the foreground service notification.
 * - Handle graceful shutdown on service stop.
 *
 * The tunnel runs on a background coroutine (Dispatchers.IO). The native
 * [NativeTunnel.runTunnel] call blocks, so we invoke it via
 * [withContext(Dispatchers.IO)].
 */
class TunnelManager(private val vpnService: OptimizerVpnService) {

    companion object {
        private const val TAG = "TunnelManager"
    }

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _status = MutableLiveData<TunnelStatus>(TunnelStatus.Stopped)
    val status: LiveData<TunnelStatus> = _status

    private var tunnelHandle: Long = 0L

    /**
     * Start the VPN tunnel.
     *
     * 1. Ask the VpnService to establish the TUN interface with the
     *    configured DNS server and excluded apps.
     * 2. Initialize the native Rust tunnel engine.
     * 3. Configure DNS-over-HTTPS endpoint in the native layer.
     * 4. Run the blocking tunnel event loop.
     *
     * The TUN interface uses:
     * - IPv4: 10.0.0.2/32
     * - IPv6: fe80::2/128
     * - Routes: all traffic (0.0.0.0/0, ::/0)
     * - DNS: the upstream IP of the DoH resolver
     */
    suspend fun startTunnel(
        dnsEndpoint: String = "https://1.1.1.1/dns-query",
        dnsUpstreamIp: String = "1.1.1.1",
        excludedApps: List<String> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Load native library
            if (!NativeTunnel.loadLibrary()) {
                _status.postValue(TunnelStatus.Error("Native library not loaded"))
                return@withContext false
            }

            // 2. Establish the TUN interface via the VpnService
            val vpnInterface = vpnService.establishTunnel(
                dnsServer = dnsUpstreamIp,
                excludedApps = excludedApps,
            )
            if (vpnInterface == null) {
                _status.postValue(TunnelStatus.Error("Failed to establish VPN interface"))
                return@withContext false
            }

            // 3. Initialize native tunnel engine
            tunnelHandle = NativeTunnel.initTunnel()
            if (tunnelHandle == 0L) {
                vpnInterface.close()
                _status.postValue(TunnelStatus.Error("Native tunnel init failed"))
                return@withContext false
            }

            // 4. Configure DNS in the native layer
            NativeTunnel.setDnsOverride(
                tunnelHandle,
                dnsEndpoint,
                dnsUpstreamIp,
                enabled = true,
            )

            _isRunning.postValue(true)
            _status.postValue(TunnelStatus.Running)

            // 5. Start the tunnel event loop (blocking — runs on IO dispatcher)
            // Extract the raw file descriptor integer from the ParcelFileDescriptor
            val fd = vpnInterface.fileDescriptor.hashCode()
            val result = NativeTunnel.runTunnel(tunnelHandle, fd)

            // The loop has exited (either via shutdown or error)
            _isRunning.postValue(false)
            if (result != 0) {
                _status.postValue(TunnelStatus.Error("Tunnel exited with code $result"))
            } else {
                _status.postValue(TunnelStatus.Stopped)
            }

            vpnInterface.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel error: ${e.message}", e)
            _status.postValue(TunnelStatus.Error(e.message ?: "Unknown error"))
            false
        }
    }

    /**
     * Stop the tunnel gracefully.
     * Signals the native loop to exit via a shutdown flag.
     */
    fun stopTunnel() {
        if (tunnelHandle != 0L) {
            NativeTunnel.shutdownTunnel(tunnelHandle)
        }
        _isRunning.postValue(false)
        _status.postValue(TunnelStatus.Stopped)
    }

    fun getStatus(): TunnelStatus = _status.value ?: TunnelStatus.Stopped

    fun cleanup() {
        stopTunnel()
    }
}

/**
 * Tunnel state for UI consumption.
 */
sealed class TunnelStatus {
    object Stopped : TunnelStatus()
    object Connecting : TunnelStatus()
    object Running : TunnelStatus()
    data class Error(val message: String) : TunnelStatus()
}
