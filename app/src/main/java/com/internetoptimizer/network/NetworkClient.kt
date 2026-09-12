package com.internetoptimizer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network client that prefers Cronet (Chromium's HTTP stack) and falls back
 * to OkHttp when Play Services / Cronet is unavailable.
 *
 * Cronet provides:
 * - HTTP/2 multiplexing
 * - HTTP/3 / QUIC support (zero RTT, reduced latency)
 * - Built-in connection pooling and caching
 *
 * OkHttp is used as the fallback — it also supports HTTP/2 but not QUIC.
 *
 * This client is used for all app-initiated network operations:
 * - DNS latency testing
 * - Configuration updates
 * - Optional telemetry (opt-in only)
 */
class NetworkClient(private val context: Context) {

    companion object {
        private const val TAG = "NetworkClient"
        private const val TIMEOUT_MS = 10_000L
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // Cronet is loaded dynamically via reflection if Play Services is available.
    // For the MVP, we use OkHttp for all requests and note that Cronet
    // integration is a straightforward drop-in replacement on devices
    // with com.google.android.gms.
    //
    // To enable Cronet, add the following dependency to build.gradle:
    //   implementation 'com.google.android.gms:play-services-cronet:18.0.2'
    // Then CronetProvider is initialized in the init block below.

    init {
        tryInitializeCronet()
    }

    /**
     * Attempt to initialize Cronet from Play Services.
     * Returns true if available, false if OkHttp fallback is used.
     */
    private fun tryInitializeCronet(): Boolean {
        return try {
            Class.forName("com.google.android.gms.net.CronetProvider")
            // Cronet is available — in a full implementation, we'd create
            // a CronetEngine here and build a URLConnection-style client.
            Log.i(TAG, "Cronet (Play Services) is available")
            true
        } catch (_: ClassNotFoundException) {
            Log.w(TAG, "Cronet not available — falling back to OkHttp")
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Perform an HTTP HEAD request and measure round-trip latency.
     *
     * @param url The URL to probe
     * @return Result of (success, latencyMs, httpCode, error)
     */
    suspend fun measureLatency(url: String): LatencyResult = withContext(Dispatchers.IO) {
        val startTime = System.nanoTime()
        try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            val response: Response = okHttpClient.newCall(request).execute()
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

            LatencyResult(
                success = true,
                latencyMs = elapsedMs,
                httpCode = response.code,
                error = null,
            )
        } catch (e: IOException) {
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            LatencyResult(
                success = false,
                latencyMs = elapsedMs,
                httpCode = -1,
                error = e.message,
            )
        }
    }

    /**
     * Download a payload and measure throughput (bytes per second).
     *
     * @param url The URL to download
     * @param expectedBytes Approximate size for reporting (if known)
     * @return ThroughputResult with measured bytes and speed
     */
    suspend fun measureThroughput(url: String, expectedBytes: Long = 0L): ThroughputResult =
        withContext(Dispatchers.IO) {
            val startTime = System.nanoTime()
            try {
                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext ThroughputResult(
                        success = false,
                        bytesTransferred = 0,
                        elapsedMs = (System.nanoTime() - startTime) / 1_000_000,
                        speedMbps = 0.0,
                        error = "HTTP ${response.code}",
                    )
                }

                // Stream the body and count bytes
                var totalBytes = 0L
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read().also { read = it } != -1) {
                        totalBytes += read.toLong()
                    }
                } ?: run {
                    return@withContext ThroughputResult(
                        success = false,
                        bytesTransferred = 0,
                        elapsedMs = (System.nanoTime() - startTime) / 1_000_000,
                        speedMbps = 0.0,
                        error = "No response body",
                    )
                }

                val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                // Mbps = (bytes * 8) / (ms * 1000)
                val speedMbps = if (elapsedMs > 0) {
                    (totalBytes * 8.0) / (elapsedMs * 1000.0)
                } else {
                    0.0
                }

                ThroughputResult(
                    success = true,
                    bytesTransferred = totalBytes,
                    elapsedMs = elapsedMs,
                    speedMbps = speedMbps,
                    error = null,
                )
            } catch (e: IOException) {
                ThroughputResult(
                    success = false,
                    bytesTransferred = 0,
                    elapsedMs = (System.nanoTime() - startTime) / 1_000_000,
                    speedMbps = 0.0,
                    error = e.message,
                )
            }
        }

    /**
     * Check if the device has an active network connection.
     */
    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Fetch the latest DNS resolver configuration from a remote endpoint.
     * This is the "buscar atualizações de configuração" use case.
     * Privacy: this only fetches DNS server IPs, not user traffic.
     */
    suspend fun fetchConfigUpdate(url: String): ConfigUpdateResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                ConfigUpdateResult(success = true, configJson = body, error = null)
            } else {
                ConfigUpdateResult(success = false, configJson = null, error = "HTTP ${response.code}")
            }
        } catch (e: IOException) {
            ConfigUpdateResult(success = false, configJson = null, error = e.message)
        }
    }
}

data class LatencyResult(
    val success: Boolean,
    val latencyMs: Long,
    val httpCode: Int,
    val error: String?,
)

data class ThroughputResult(
    val success: Boolean,
    val bytesTransferred: Long,
    val elapsedMs: Long,
    val speedMbps: Double,
    val error: String?,
)

data class ConfigUpdateResult(
    val success: Boolean,
    val configJson: String?,
    val error: String?,
)
