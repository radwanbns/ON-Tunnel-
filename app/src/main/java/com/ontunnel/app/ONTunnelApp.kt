package com.ontunnel.app

import de.blinkt.openvpn.core.ICSOpenVPNApplication

class ONTunnelApp : ICSOpenVPNApplication() {
    override fun onCreate() {
        super.onCreate()
        // Schedule background subscription updates (needs internet; runs without UI).
        SubscriptionSync.schedule(this)
    }
}
