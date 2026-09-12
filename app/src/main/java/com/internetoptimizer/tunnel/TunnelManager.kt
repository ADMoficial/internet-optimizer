package com.internetoptimizer.tunnel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages the lifecycle of the local VPN tunnel.
 *
 * MVP: TunnelManager is temporarily stubbed to return
 * [TunnelStatus.Stopped] since the native Rust tunnel
 * is not available in this phase. The UI layer still
 * works and responds to toggle gestures.
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
     * MVP: Returns immediately with false — native tunnel unavailable.
     */
    suspend fun startTunnel(
        dnsEndpoint: String = "https://1.1.1.1/dns-query",
        dnsUpstreamIp: String = "1.1.1.1",
        excludedApps: List<String> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // MVP: Native library not available
            Log.w(TAG, "Tunnel disabled in MVP mode — native library unavailable")
            _status.postValue(TunnelStatus.Error("VPN temporariamente indisponível. Em breve!"))
            false
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel error: ${e.message}", e)
            _status.postValue(TunnelStatus.Error(e.message ?: "Unknown error"))
            false
        }
    }

    /**
     * Stop the tunnel gracefully.
     * MVP: No-op.
     */
    fun stopTunnel() {
        Log.w(TAG, "stopTunnel called — no native tunnel to stop (MVP mode)")
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
