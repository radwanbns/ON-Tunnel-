package com.ontunnel.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Runtime package-name and signing-certificate verification.
 *
 * Both expected values are obfuscated in DEX. This raises the effort needed
 * to inspect the checks, but is not cryptographic secrecy or bypass-proof.
 */
internal object AppIntegrity {

    private const val MASK = 0x5A
    private const val PACKAGE_MASK = 0x23

    private fun expectedPackageName(): String {
        val encoded = intArrayOf(64, 76, 78, 13, 76, 77, 87, 86, 77, 77, 70, 79, 13, 66, 83, 83)
        return String(CharArray(encoded.size) { index ->
            (encoded[index] xor PACKAGE_MASK).toChar()
        })
    }

    private fun expectedDigest(): ByteArray {
        val encoded = intArrayOf(117, 137, 175, 19, 101, 50, 181, 198, 3, 9, 92, 171, 4, 110, 202, 118, 148, 242, 187, 158, 225, 191, 98, 158, 88, 218, 173, 180, 45, 198, 250, 80)
        return ByteArray(encoded.size) { index ->
            (encoded[index] xor MASK).toByte()
        }
    }

    fun isAppValid(context: Context): Boolean {
        return try {
            // Reject an APK rebuilt under a different application ID.
            if (context.packageName != expectedPackageName()) return false

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
