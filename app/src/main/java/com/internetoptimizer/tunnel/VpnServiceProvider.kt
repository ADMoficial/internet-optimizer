package com.internetoptimizer.tunnel

import android.net.VpnService

/**
 * Singleton that holds a reference to the active [VpnService] instance.
 *
 * The TUN interface must be established within the VpnService process
 * (it has the system permission to do so). The TunnelService (foreground
 * service) needs access to this VpnService to create the TUN builder.
 *
 * We use a simple singleton pattern instead of a full DI framework
 * (like Hilt) to keep the app lightweight.
 */
object VpnServiceProvider {
    private var vpnService: VpnService? = null

    fun set(service: VpnService) {
        vpnService = service
    }

    fun get(): VpnService? = vpnService

    fun clear() {
        vpnService = null
    }
}
