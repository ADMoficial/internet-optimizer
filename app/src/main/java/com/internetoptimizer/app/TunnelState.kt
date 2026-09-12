package com.internetoptimizer.app

/**
 * Tunnel state enum used by the UI and service.
 *
 * These are user-friendly states (no technical jargon like "tun2socks"
 * or "VpnService" visible to end users).
 */
enum class TunnelState {
    Stopped,     // Tunnel is not running
    Starting,    // User tapped the toggle, requesting permission
    Connecting,  // TUN is being established, native engine starting
    Running,     // Tunnel is active and forwarding traffic
}
