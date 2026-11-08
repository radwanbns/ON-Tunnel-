package com.ontunnel.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object AppIntegrity {

    private const val EXPECTED_SHA256 = "E5:43:2A:9F:EC:AA:79:42:54:42:8D:D1:18:EE:10:AB:B7:B4:4B:0C:6F:C6:4D:6F:97:EB:9A:9C:17:6E:2D:68"

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
