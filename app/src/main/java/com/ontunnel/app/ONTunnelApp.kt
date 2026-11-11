package com.ontunnel.app

import android.app.Application

class ONTunnelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Schedule background subscription updates (needs internet; runs without UI).
        SubscriptionSync.schedule(this)
    }
}
