package com.ontunnel.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background subscription sync: updates locked servers whenever the device
 * has internet, without requiring the UI to be open.
 */
object SubscriptionSync {
    private const val TAG = "SubscriptionSync"
    private const val UNIQUE_PERIODIC = "on_tunnel_sub_periodic"
    private const val UNIQUE_ONCE = "on_tunnel_sub_once"
    private const val MIN_INTERVAL_MS = 5 * 60 * 1000L // avoid hammering the URL

    @Volatile private var lastAttemptMs = 0L
    private val networkCallbackRegistered = AtomicBoolean(false)

    fun isConfigured(): Boolean {
        val url = SecureStrings.subscriptionUrl
        return url.isNotBlank() &&
            !url.contains("example.com") &&
            (url.startsWith("http://") || url.startsWith("https://"))
    }

    /** Schedule periodic + one-shot work (call from Application / MainActivity). */
    fun schedule(context: Context) {
        if (!isConfigured()) {
            Log.i(TAG, "Subscription URL not configured – background sync skipped")
            return
        }
        val app = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )

        enqueueNow(app)
        registerNetworkCallback(app)
    }

    /** One-time update as soon as network is available. */
    fun enqueueNow(context: Context) {
        if (!isConfigured()) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val once = OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_ONCE,
            ExistingWorkPolicy.KEEP,
            once
        )
    }

    private fun registerNetworkCallback(context: Context) {
        if (!networkCallbackRegistered.compareAndSet(false, true)) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network available – enqueue subscription update")
                    enqueueNow(context)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        enqueueNow(context)
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "NetworkCallback registration failed: ${e.message}")
            networkCallbackRegistered.set(false)
        }
    }

    /**
     * Fetch subscription and merge into local store.
     * @return number of new locked servers added, or -1 on failure / skip
     */
    fun syncNow(context: Context): Int {
        if (!isConfigured()) return -1
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < MIN_INTERVAL_MS) {
            Log.i(TAG, "Skip sync – last attempt was recent")
            return 0
        }
        lastAttemptMs = now

        val result = fetchAndParse(SecureStrings.subscriptionUrl)
        return result.fold(
            onSuccess = { fresh ->
                val store = ServerStore(context)
                val current = store.load()
                val oldLocked = current.filter { it.locked }.associateBy { it.name }
                val manual = current.filter { !it.locked }
                val newLocked = fresh.distinctBy { it.name }.map { e ->
                    oldLocked[e.name]?.let { e.copy(id = it.id) } ?: e
                }
                val added = newLocked.count { it.name !in oldLocked }
                val merged = newLocked + manual
                store.save(merged)
                if (merged.none { it.id == store.selectedId }) {
                    store.selectedId = merged.firstOrNull()?.id.orEmpty()
                }
                Log.i(TAG, "Subscription updated – $added new server(s), total locked=${newLocked.size}")
                added
            },
            onFailure = { e ->
                Log.w(TAG, "Subscription update failed: ${e.message}")
                -1
            }
        )
    }

    fun fetchAndParse(url: String): Result<List<ServerEntry>> = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 20000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 ONTunnel/1.2")
            setRequestProperty("Accept", "text/plain, */*")
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            error("HTTP $code")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
        conn.disconnect()
        if (body.isBlank()) error("Subscription is empty")

        val lines = tryDecode(body).lines().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val entries = mutableListOf<ServerEntry>()
        val errors = mutableListOf<String>()
        for (line in lines) {
            try {
                val parsed = V2RayConfigParser.parse(line)
                entries += ServerEntry(name = parsed.name, config = parsed.config, uri = "", locked = true)
            } catch (e: Exception) {
                errors += (e.message ?: "parse error")
            }
        }
        if (entries.isEmpty()) {
            error(
                if (lines.isEmpty()) "No servers found in subscription"
                else "Parsed 0/${lines.size} lines. First error: ${errors.firstOrNull() ?: "unknown"}"
            )
        }
        entries
    }

    private fun tryDecode(body: String): String {
        if (body.contains("://") || body.trimStart().startsWith("{")) return body
        for (flag in listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP, Base64.DEFAULT or Base64.NO_WRAP)) {
            try {
                val decoded = String(Base64.decode(body, flag), Charsets.UTF_8).trim()
                if (decoded.contains("://") || decoded.startsWith("{")) return decoded
            } catch (_: Exception) { }
        }
        return body
    }
}

class SubscriptionUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val added = SubscriptionSync.syncNow(applicationContext)
            if (added >= 0) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.w("SubscriptionSync", "Worker error: ${e.message}")
            Result.retry()
        }
    }
}
