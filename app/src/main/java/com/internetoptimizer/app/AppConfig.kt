package com.internetoptimizer.app

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Application-level configuration settings for the Internet Optimizer.
 *
 * This data class holds all user-configurable parameters that control
 * how the tunnel operates: DNS endpoint, optional proxy, excluded apps,
 * and whether the tunnel should auto-start on boot.
 *
 * It is persisted via SharedPreferences through the ConfigManager.
 */
@Parcelize
data class AppConfig(
    val dnsEndpoint: String = "https://1.1.1.1/dns-query",
    val dnsUpstreamIp: String = "1.1.1.1",
    val isProxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.SOCKS5,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val excludedApps: List<String> = emptyList(),
    val autoStart: Boolean = false,
    val telemetryOptIn: Boolean = false,
) : Parcelable

/**
 * Supported proxy protocols for forwarding tunnel traffic
 * through an external proxy server.
 */
enum class ProxyType {
    SOCKS5,
    HTTP_CONNECT,
}
