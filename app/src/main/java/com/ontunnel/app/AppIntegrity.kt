package com.ontunnel.app

import android.content.Context

/**
 * Offline integrity gate. The signing-certificate and package checks run in native code.
 * A missing/modified native library is treated as an integrity failure.
 */
internal object AppIntegrity {
    fun isAppValid(context: Context): Boolean {
        return try {
            NativeIntegrity.verifyNative(context.applicationContext)
        } catch (_: Throwable) {
            false
        }
    }

    fun enforce(context: Context) {
        if (!isAppValid(context)) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
