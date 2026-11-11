package com.ontunnel.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String
)

internal object AppUpdater {
    private const val RELEASE_API =
        "https://api.github.com/repos/radwanbns/ON-Tunnel-/releases/latest"

    /**
     * Release convention:
     * - tag: v7, v8, v9... (must match app versionCode)
     * - assets: ON-Tunnel-arm64-v8a.apk, ON-Tunnel-armeabi-v7a.apk,
     *   ON-Tunnel-x86_64.apk, ON-Tunnel-x86.apk
     * APKs must be signed with the same certificate as the installed app.
     */
    fun checkForUpdate(context: Context): AppUpdateInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ON-Tunnel-Android")
            }
            if (connection.responseCode !in 200..299) return null
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (release.optBoolean("draft", true) || release.optBoolean("prerelease", false)) return null

            val releaseCode = release.optString("tag_name").removePrefix("v").toLongOrNull()
                ?: return null
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            if (releaseCode <= currentCode) return null

            val abi = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
                ?: return null
            val assets = release.optJSONArray("assets") ?: return null
            var downloadUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name") == "ON-Tunnel-$abi.apk") {
                    downloadUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    break
                }
            }
            val apk = downloadUrl ?: return null
            AppUpdateInfo(
                versionCode = releaseCode,
                versionName = release.optString("name").ifBlank { "v$releaseCode" },
                apkUrl = apk
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun installDownloaded(context: Context): Boolean {
        val apk = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "ON-Tunnel-update.apk")
        if (!apk.isFile || apk.length() == 0L) return false
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", apk
            )
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(install)
            true
        } catch (_: Exception) {
            false
        }
    }
}
