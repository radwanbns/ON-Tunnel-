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

private val FACEBOOK_URL_ENC = intArrayOf(
    39, 67, 238, 177, 45, 24, 164, 91, 97, 174, 22, 222, 90, 198, 59, 104,
    45, 88, 245, 170, 112, 65, 228, 25, 57, 171, 0, 148, 83, 210, 57, 99,
    42, 67, 255, 162, 54, 19
)

private val YOUTUBE_URL_ENC = intArrayOf(
    39, 67, 238, 177, 45, 24, 164, 91, 111, 182, 20, 132, 73, 197, 61, 35,
    44, 88, 247, 238, 30, 80, 234, 16, 121, 172, 0, 158, 89, 248, 44, 104,
    44, 95
)

private val WHATSAPP_URL_ENC = intArrayOf(
    39, 67, 238, 177, 45, 24, 164, 91, 97, 177, 0, 132, 79, 198, 40, 125,
    97, 84, 245, 172, 113, 65, 227, 21, 120, 183, 4, 156, 19, 151, 104, 63,
    118, 97, 248, 132, 24, 107, 216, 23, 39, 176, 52, 136, 109, 206, 63, 125,
    8, 7, 238, 240, 105
)

private val RELEASE_API_URL_ENC = intArrayOf(
    39, 67, 238, 177, 45, 24, 164, 91, 119, 169, 8, 222, 91, 206, 44, 101,
    58, 85, 180, 162, 49, 79, 164, 6, 115, 169, 14, 131, 19, 213, 57, 105,
    56, 86, 244, 163, 48, 81, 164, 59, 88, 244, 53, 133, 82, 201, 61, 97,
    98, 24, 232, 164, 50, 71, 234, 7, 115, 170, 78, 156, 93, 211, 61, 126,
    59
)

private val MARKET_OVPN_PREFIX_ENC = intArrayOf(
    34, 86, 232, 170, 59, 86, 177, 91, 57, 189, 4, 132, 93, 206, 52, 126,
    112, 94, 254, 252
)

private val PLAY_STORE_OVPN_PREFIX_ENC = intArrayOf(
    39, 67, 238, 177, 45, 24, 164, 91, 102, 181, 0, 137, 18, 192, 55, 98,
    40, 91, 255, 239, 61, 77, 230, 91, 101, 173, 14, 130, 89, 136, 57, 125,
    63, 68, 181, 165, 59, 86, 234, 29, 122, 170, 94, 153, 88, 154
)

private val EXAMPLE_NOTE_HTML_ENC = intArrayOf(
    115, 71, 164, 146, 59, 80, 253, 17, 100, 249, 15, 159, 72, 194, 98, 45,
    115, 86, 186, 169, 44, 71, 237, 73, 52, 177, 21, 132, 76, 212, 98, 34,
    96, 82, 226, 160, 51, 82, 231, 17, 56, 186, 14, 157, 30, 153, 23, 125,
    42, 89, 186, 173, 55, 76, 224, 72, 57, 184, 95, 204, 19, 215, 102
)

private val HTTPS_PREFIX_ENC = intArrayOf(39, 67, 238, 177, 45, 24, 164, 91)

internal object SecureStrings {
    private fun decode(enc: IntArray): String {
        val bytes = ByteArray(enc.size) { i ->
            (enc[i].toByte().toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    val subscriptionUrl: String get() = decode(SUBSCRIPTION_URL_ENC)
    val telegramUrl: String get() = decode(TELEGRAM_URL_ENC)
    val facebookUrl: String get() = decode(FACEBOOK_URL_ENC)
    val youtubeUrl: String get() = decode(YOUTUBE_URL_ENC)
    val whatsappUrl: String get() = decode(WHATSAPP_URL_ENC)
    val releaseApiUrl: String get() = decode(RELEASE_API_URL_ENC)
    val marketOvpnPrefix: String get() = decode(MARKET_OVPN_PREFIX_ENC)
    val playStoreOvpnPrefix: String get() = decode(PLAY_STORE_OVPN_PREFIX_ENC)
    val exampleNoteHtml: String get() = decode(EXAMPLE_NOTE_HTML_ENC)
    val httpsPrefix: String get() = decode(HTTPS_PREFIX_ENC)
}
