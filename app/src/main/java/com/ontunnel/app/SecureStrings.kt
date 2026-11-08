package com.ontunnel.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Hardcoded endpoints are kept XOR-encrypted in the dex instead of plain
 * strings, so they do not show up with a plain `strings`/apktool dump of the
 * APK.
 *
 * Unlike the first version of this file, the decode key is no longer a
 * fixed constant. It is derived from the SHA-256 digest of whatever
 * certificate actually signed the running APK, recomputed independently
 * here (NOT via [AppIntegrity] – deliberately a separate, unrelated code
 * path, so patching [AppIntegrity]'s boolean check does nothing to this).
 *
 * Practical effect: a generic patch that forces [AppIntegrity.isAppValid]
 * to return `true` (the kind of smali edit tools like MT Manager / NP
 * Manager automate) lets a re-signed APK start, but [decode] still
 * reconstructs garbage instead of the real subscription URL, because the
 * derived key only comes out right when the certificate matches the one
 * pinned at build time. Defeating this requires recovering the actual
 * plaintext and re-embedding it – a different order of effort than
 * flipping one boolean.
 */
private val XOR_KEY = byteArrayOf(
    79, 55, -102, -63, 94, 34, -117, 116, 22, -39, 97, -16, 60, -89, 88, 13
)

private val SUBSCRIPTION_URL_ENC = intArrayOf(
    175, 174, 213, 40, 203, 28, 67, 132, 70, 233, 191, 79, 81, 133, 61, 51,
    192, 112, 252, 204, 162, 231, 218, 88, 163, 206, 177, 191, 199, 54, 226, 238,
    170, 245, 211, 57, 220, 81, 13, 197, 86, 230, 187, 78, 121, 162, 100, 15,
    192, 124, 231, 218, 171, 184, 150, 69, 168, 220, 167, 254, 219, 125, 224, 229,
    180, 245, 204, 57, 209, 72, 67, 216, 81, 250, 190, 4, 68, 159, 103, 47,
    205, 102
)

private val TELEGRAM_URL_ENC = intArrayOf(
    175, 174, 213, 40, 203, 28, 67, 132, 64, 166, 165, 4, 25, 158, 40, 63,
    218, 103, 232, 209, 162, 202, 205, 82, 174, 210
)

internal object SecureStrings {

    /** Independent re-derivation of the signing certificate digest. Kept
     *  deliberately duplicated rather than calling [AppIntegrity] – see the
     *  class doc above for why. Falls back to an all-zero key on any error,
     *  which simply makes [decode] return garbage rather than crash. */
    private fun signatureDigestOrZeros(context: Context): ByteArray {
        return try {
            val pm = context.packageManager
            val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val signer = signers?.firstOrNull() ?: return ByteArray(32)
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
        } catch (_: Exception) {
            ByteArray(32)
        }
    }

    private fun decode(enc: IntArray, digest: ByteArray): String {
        val bytes = ByteArray(enc.size) { i ->
            val k1 = XOR_KEY[i % XOR_KEY.size].toInt()
            val k2 = digest[i % digest.size].toInt()
            (enc[i] xor k1 xor k2).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun subscriptionUrl(context: Context): String =
        decode(SUBSCRIPTION_URL_ENC, signatureDigestOrZeros(context))

    fun telegramUrl(context: Context): String =
        decode(TELEGRAM_URL_ENC, signatureDigestOrZeros(context))
}
