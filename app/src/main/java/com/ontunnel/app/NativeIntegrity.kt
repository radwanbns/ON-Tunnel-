package com.ontunnel.app

import android.content.Context

/**
 * JNI bridge to the offline native integrity check.
 * Keep this class name stable: the C++ JNI entry point is bound to it.
 */
internal object NativeIntegrity {
    init {
        System.loadLibrary("ontunnel_guard")
    }

    external fun verifyNative(context: Context): Boolean
}
