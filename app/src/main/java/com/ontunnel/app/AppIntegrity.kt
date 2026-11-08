package com.ontunnel.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object AppIntegrity {

    private const val EXPECTED_SHA256 = "E8:C2:3E:49:01:AB:E2:17:39:A5:D7:00:9F:2F:F6:14:0F:73:B5:9E:A6:88:6F:2B:42:77:F2:D1:A2:42:F5:09"

    private fun expectedDigest(): ByteArray {
        val hex = EXPECTED_SHA256.replace(":", "").trim()
        require(hex.length == 64 && hex.all { it in "0123456789abcdefABCDEF" })
        return ByteArray(32) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun isAppValid(context: Context): Boolean {
        return try {
            val expected = expectedDigest()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Ask Android's package manager to verify the installed signing certificate.
                context.packageManager.hasSigningCertificate(
                    context.packageName,
                    expected,
                    PackageManager.CERT_INPUT_SHA256
                )
            } else {
                @Suppress("DEPRECATION")
                val signatures = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures ?: return false

                signatures.any { signature ->
                    val actual = MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray())
                    MessageDigest.isEqual(actual, expected)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun enforce(context: Context) {
        if (!isAppValid(context)) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
