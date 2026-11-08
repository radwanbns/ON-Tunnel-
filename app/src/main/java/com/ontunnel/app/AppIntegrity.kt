package com.ontunnel.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Runtime signing-certificate verification.
 *
 * The expected digest is XOR-obfuscated in the DEX rather than stored as
 * a readable SHA-256 string. This is obfuscation only, not cryptographic
 * secrecy; the APK's signing certificate remains the trust anchor.
 */
internal object AppIntegrity {

    private const val MASK = 0x5A

    private fun expectedDigest(): ByteArray {
        val encoded = intArrayOf(191, 25, 112, 197, 182, 240, 35, 24, 14, 24, 215, 139, 66, 180, 74, 241, 237, 238, 17, 86, 53, 156, 23, 53, 205, 177, 192, 198, 77, 52, 119, 50)
        return ByteArray(encoded.size) { index ->
            (encoded[index] xor MASK).toByte()
        }
    }

    fun isAppValid(context: Context): Boolean {
        return try {
            val expected = expectedDigest()
            val packageManager = context.packageManager

            val actualSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                info.signingInfo?.apkContentsSigners ?: return false
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                ).signatures ?: return false
            }

            actualSigners.any { signer ->
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(signer.toByteArray())
                MessageDigest.isEqual(actual, expected)
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
