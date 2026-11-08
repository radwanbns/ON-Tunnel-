package com.xraytunnel.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object AppIntegrity {

    // ضع بصمة شهادة Debug SHA-256 هنا بعد استخراجها
    private const val EXPECTED_SHA256 = "2d831523ad55f1f08c1760e77086973f068b8289458f9150befc766069539873"

    fun isAppValid(context: Context): Boolean {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.signingInfo.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

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
