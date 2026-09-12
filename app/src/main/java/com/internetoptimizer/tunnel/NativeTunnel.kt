package com.internetoptimizer.tunnel

import android.util.Log

/**
 * JNI bindings for the Rust native tunnel library (`libtun_interface.so`).
 *
 * TODO (MVP without Rust): Temporarily disabled for the MVP phase.
 * Native tunnel functionality will be re-enabled when the Rust crate
 * is fully built and integrated via CI/CD.
 *
 * All native methods below return default/fallback values.
 */
object NativeTunnel {

    private const val TAG = "NativeTunnel"

    /**
     * Load the native tunnel library.
     * MVP: Always returns false — native library not available.
     */
    fun loadLibrary(): Boolean {
        Log.w(TAG, "Native library not available in MVP mode")
        return false
    }

    /**
     * Initialize the tunnel engine.
     * MVP: Returns 0 (invalid handle) — no native engine.
     */
    @JvmStatic
    private external fun nativeInitTunnel(): Long
    fun initTunnel(): Long = 0L

    /**
     * Run the tunnel event loop.
     * MVP: Returns -1 (not supported).
     */
    @JvmStatic
    private external fun nativeRunTunnel(handle: Long, tunFd: Int): Int
    fun runTunnel(handle: Long, tunFd: Int): Int = -1

    /**
     * Signal the tunnel loop to shut down.
     * MVP: No-op.
     */
    @JvmStatic
    private external fun nativeShutdownTunnel(handle: Long)
    fun shutdownTunnel(handle: Long) {
        Log.w(TAG, "Native shutdown not available in MVP mode")
    }

    /**
     * Update the DNS-over-HTTPS resolver endpoint.
     * MVP: No-op.
     */
    @JvmStatic
    private external fun nativeSetDnsOverride(handle: Long, endpoint: String, upstreamIp: String, enabled: Boolean)
    fun setDnsOverride(handle: Long, endpoint: String, upstreamIp: String, enabled: Boolean) {
        Log.w(TAG, "Native DNS override not available in MVP mode")
    }

    /**
     * Configure or clear the external proxy.
     * MVP: No-op.
     */
    @JvmStatic
    private external fun nativeSetProxy(handle: Long, proxyType: Int, host: String, port: Int)
    fun setProxy(handle: Long, proxyType: Int, host: String, port: Int) {
        Log.w(TAG, "Native proxy not available in MVP mode")
    }
}
