package com.ontunnel.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.security.MessageDigest

/**
 * Runtime package-name and signing-certificate verification.
 *
 * This is ONE layer among several (see [ThreatDetector], [SecureStrings] and
 * the repeated call sites in MainActivity / ONTunnelVpnService). No single
 * on-device check is bypass-proof against someone with full control of
 * their own device – the goal here is to raise the cost of a generic,
 * one-click patch (the kind tools like MT Manager / NP Manager apply) well
 * above "flip one boolean", not to claim unconditional tamper-proofing.
 *
 * Both expected values are obfuscated in DEX. This raises the effort needed
 * to inspect the checks, but is not cryptographic secrecy by itself – the
 * real teeth come from [SecureStrings] deriving its decryption key from the
 * *actual* runtime certificate digest computed below, so patching this
 * class alone does not make the decrypted config correct again.
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

    // SHA-256 of the release signing certificate (see /mnt/user-data/outputs for the
    // generated keystore + RELEASE_SIGNING.md). Regenerate this array with
    // tools/encode_secrets.py if you ever rotate the release key.
    private fun expectedDigest(): ByteArray {
        val encoded = intArrayOf(
            210, 183, 97, 195, 188, 94, 189, 133, 120, 11, 243, 203, 80, 17, 75, 12,
            160, 127, 73, 36, 195, 237, 104, 25, 129, 57, 239, 123, 213, 229, 131, 214
        )
        return ByteArray(encoded.size) { index ->
            (encoded[index] xor MASK).toByte()
        }
    }

    /** Recomputes the SHA-256 digest of the certificate that actually signed
     *  the running APK. Returns null if it cannot be read. Exposed so other,
     *  independent classes (notably [SecureStrings]) can derive values from
     *  it without going through [isAppValid] – that way neutralizing the
     *  boolean check in this object does not help decrypt anything. */
    fun currentSignatureDigest(context: Context): ByteArray? = try {
        val packageManager = context.packageManager
        val actualSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            ).signatures
        }
        val signer = actualSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
    } catch (_: Exception) {
        null
    }

    fun isAppValid(context: Context): Boolean {
        return try {
            // Reject an APK rebuilt under a different application ID.
            if (context.packageName != expectedPackageName()) return false

            val actual = currentSignatureDigest(context) ?: return false
            MessageDigest.isEqual(actual, expectedDigest())
        } catch (_: Exception) {
            false
        }
    }

    /** True when this process is running from a build that has
     *  android:debuggable set (release builds must never ship like this –
     *  it allows `adb shell run-as`, JDWP debugger attach without root,
     *  and is exactly what most generic smali-patching tool-chains expect). */
    fun isDebuggableBuild(context: Context): Boolean = try {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    } catch (_: Exception) {
        false
    }

    fun isDebuggerAttached(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    /** Combines every static signal this object knows about. Call this (not
     *  just [isAppValid]) from every enforcement point. */
    fun isEnvironmentTrusted(context: Context): Boolean =
        isAppValid(context) && !isDebuggableBuild(context)

    fun enforce(context: Context) {
        if (!isEnvironmentTrusted(context)) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
