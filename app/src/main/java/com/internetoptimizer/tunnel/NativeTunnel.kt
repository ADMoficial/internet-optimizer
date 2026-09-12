package com.internetoptimizer.tunnel

import android.util.Log

/**
 * JNI bindings for the Rust native tunnel library (`libtun_interface.so`).
 *
 * Each external function delegates to the corresponding `#[no_mangle]
 * extern "C"` function in `src/lib.rs` of the Rust crate `tun_interface`.
 *
 * IMPORTANT: The native library must be loaded via [loadLibrary] before
 * any of these functions are called. The library is built from the
 * `rust/` module using a Gradle `ExternalNativeBuild` configuration
 * (see `build.gradle` in the app module).
 *
 * Thread-safety: The native functions are NOT thread-safe. The caller
 * must ensure that init/run/shutdown are not invoked concurrently.
 */
object NativeTunnel {

    private const val TAG = "NativeTunnel"

    /**
     * Load the native tunnel library. Should be called once at app startup
     * before any other native methods are used.
     *
     * @return true if the library loaded successfully, false otherwise
     */
    fun loadLibrary(): Boolean {
        return try {
            System.loadLibrary("tun_interface")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library 'tun_interface'", e)
            false
        }
    }

    /**
     * Initialize the tunnel engine and return an opaque handle.
     * The handle must be stored and passed to [runTunnel] and [shutdownTunnel].
     *
     * @return a Long representing the opaque pointer to the native Tunnel struct
     */
    @JvmStatic
    private external fun nativeInitTunnel(): Long

    fun initTunnel(): Long = nativeInitTunnel()

    /**
     * Run the tunnel event loop on the calling thread.
     * Blocks until shutdown is called or an error occurs.
     *
     * @param handle The handle returned by [initTunnel]
     * @param tunFd  The TUN socket file descriptor from VpnService.Builder.establishVpn()
     * @return 0 on success, -1 on error
     */
    @JvmStatic
    private external fun nativeRunTunnel(handle: Long, tunFd: Int): Int

    fun runTunnel(handle: Long, tunFd: Int): Int = nativeRunTunnel(handle, tunFd)

    /**
     * Signal the tunnel loop to shut down gracefully.
     * Non-blocking — the loop exits on its next iteration.
     */
    @JvmStatic
    private external fun nativeShutdownTunnel(handle: Long)

    fun shutdownTunnel(handle: Long) {
        nativeShutdownTunnel(handle)
    }

    /**
     * Update the DNS-over-HTTPS resolver endpoint at runtime.
     *
     * @param handle     Tunnel handle from [initTunnel]
     * @param endpoint   DoH URL, e.g. "https://1.1.1.1/dns-query"
     * @param upstreamIp The IP address of the DoH server (e.g. "1.1.1.1")
     * @param enabled    Whether DoH interception is active
     */
    @JvmStatic
    private external fun nativeSetDnsOverride(
        handle: Long,
        endpoint: String,
        upstreamIp: String,
        enabled: Boolean,
    )

    fun setDnsOverride(handle: Long, endpoint: String, upstreamIp: String, enabled: Boolean) {
        nativeSetDnsOverride(handle, endpoint, upstreamIp, enabled)
    }

    /**
     * Configure or clear the external proxy.
     *
     * @param handle     Tunnel handle
     * @param proxyType  0 = none, 1 = SOCKS5, 2 = HTTP CONNECT
     * @param host       Proxy hostname/IP
     * @param port       Proxy port
     */
    @JvmStatic
    private external fun nativeSetProxy(
        handle: Long,
        proxyType: Int,
        host: String,
        port: Int,
    )

    fun setProxy(handle: Long, proxyType: Int, host: String, port: Int) {
        nativeSetProxy(handle, proxyType, host, port)
    }
}
