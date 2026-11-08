
package com.ontunnel.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object AppIntegrity {

    private const val EXPECTED_SHA256 = "E5:43:2A:9F:EC:AA:79:42:54:42:8D:D1:18:EE:10:AB:B7:B4:4B:0C:6F:C6:4D:6F:97:EB:9A:9C:17:6E:2D:68"

    fun isAppValid(context: Context): Boolean {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures
            } ?: return false

            signatures.any { signature ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())

                val actual = digest.joinToString("") {
                    "%02X".format(it)
                }

                actual.equals(
                    EXPECTED_SHA256.replace(":", "").trim(),
                    ignoreCase = true
                )
            }
        } catch (_: Exception) {
            false
        }
    }

    fun enforce(context: Context) {
        if (!isAppValid(context)) {
            android.os.Process.killProcess(
                android.os.Process.myPid()
            )
        }
    }
}
