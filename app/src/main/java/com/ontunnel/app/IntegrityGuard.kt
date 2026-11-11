package com.ontunnel.app

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Detects a repackaged / re-signed APK (someone edited classes.dex, rebuilt
 * and signed it with a different key) by comparing the certificate that
 * actually signed the running app against the one pinned below.
 *
 * Setup (one time, after you have a signed release build):
 *   1. Leave EXPECTED_SHA256 blank and run [status] once – it logs
 *      "ON Tunnel/Integrity" with the running app's certificate hash via
 *      Log.w, or call [currentSignatureSha256] from anywhere (e.g. show it
 *      in a Toast) to read it on-device.
 *   2. Paste that hash into EXPECTED_SHA256 below and rebuild.
 * From then on [status] returns MISMATCH for any APK re-signed with a
 * different key, including one where the dex was modified and reassembled.
 *
 * This cannot stop someone with full control of their own device from
 * patching the code (no on-device protection scheme can); it only makes a
 * silently redistributed, tampered copy detectable and able to be blocked.
 */
internal object IntegrityGuard {
    // SHA-256 of the signing certificate, lowercase hex, no separators.
    // Leave blank to disable the check (default – fill this in for your own signed builds).
    private const val EXPECTED_SHA256 = ""

    enum class Status { DISABLED, OK, MISMATCH, ERROR }

    fun status(context: Context): Status {
        if (EXPECTED_SHA256.isBlank()) return Status.DISABLED
        val actual = currentSignatureSha256(context) ?: return Status.ERROR
        return if (actual.equals(EXPECTED_SHA256, ignoreCase = true)) Status.OK else Status.MISMATCH
    }

    fun currentSignatureSha256(context: Context): String? = try {
        val pm = context.packageManager
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo
            if (signingInfo?.hasMultipleSigners() == true) signingInfo.apkContentsSigners
            else signingInfo?.signingCertificateHistory ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        val sig = signatures.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
        digest.joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }
}
