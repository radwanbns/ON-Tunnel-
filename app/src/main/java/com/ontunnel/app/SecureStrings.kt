package com.ontunnel.app

/**
 * Hardcoded endpoints are kept XOR-encrypted in the dex instead of plain
 * strings, so they do not show up with a plain `strings`/apktool dump of the
 * APK. This is obfuscation, not real cryptography – combined with R8
 * shrinking/renaming (see proguard-rules.pro) and [IntegrityGuard] it raises
 * the bar for casually inspecting or repackaging the app.
 */
private val XOR_KEY = byteArrayOf(
    79, 55, -102, -63, 94, 34, -117, 116, 22, -39, 97, -16, 60, -89, 88, 13
)

private val SUBSCRIPTION_URL_ENC = intArrayOf(
    39, 67, 238, 177, 45, 24, 164, 91, 100, 184, 22, 222, 91, 206, 44, 101,
    58, 85, 239, 178, 59, 80, 232, 27, 120, 173, 4, 158, 72, 137, 59, 98,
    34, 24, 232, 160, 58, 85, 234, 26, 116, 183, 18, 223, 115, 233, 117, 89,
    58, 89, 244, 164, 50, 15, 164, 6, 115, 191, 18, 223, 84, 194, 57, 105,
    60, 24, 247, 160, 55, 76, 164, 7, 115, 171, 23, 149, 78, 212, 118, 121,
    55, 67
)

private val TELEGRAM_URL_ENC = intArrayOf(
    39, 67, 238, 177, 45, 24, 164, 91, 98, 247, 12, 149, 19, 213, 57, 105,
    32, 66, 251, 175, 59, 125, 255, 17, 117, 177
)

internal object SecureStrings {
    private fun decode(enc: IntArray): String {
        val bytes = ByteArray(enc.size) { i ->
            (enc[i].toByte().toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    val subscriptionUrl: String get() = decode(SUBSCRIPTION_URL_ENC)
    val telegramUrl: String get() = decode(TELEGRAM_URL_ENC)
}
