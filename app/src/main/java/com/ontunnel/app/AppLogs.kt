package com.ontunnel.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class AppLogEntry(val id: Long, val time: String, val message: String)

internal object AppLogs {
    private const val PREFS = "on_tunnel_logs"
    private const val KEY = "entries"
    private const val LIMIT = 400
    private const val KEY_SERVER_DETAILS_VISIBLE = "server_details_visible"
    @Volatile private var lastLifecycleMessage: String? = null
    @Volatile private var lastLifecycleMessageAt: Long = 0L

    @Synchronized fun setServerDetailsVisible(context: Context, visible: Boolean, clearWhenHidden: Boolean = false) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SERVER_DETAILS_VISIBLE, visible).apply()
        if (!visible && clearWhenHidden) clear(context)
    }

    @Synchronized fun add(context: Context, message: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val detailsVisible = prefs.getBoolean(KEY_SERVER_DETAILS_VISIBLE, true)
        // For locked subscription profiles, retain only generic connection lifecycle messages.
        if (!detailsVisible && message !in setOf("Tunnel disconnected", "User requested VPN disconnect", "V2Ray tunnel connected", "SSH tunnel connected")) return
        val now = System.currentTimeMillis()
        // Prevent duplicate lifecycle callbacks from producing repeated identical log rows.
        if (message in setOf("V2Ray tunnel connected", "SSH tunnel connected", "Tunnel disconnected") &&
            lastLifecycleMessage == message && now - lastLifecycleMessageAt < 5_000L) return
        if (message in setOf("V2Ray tunnel connected", "SSH tunnel connected", "Tunnel disconnected")) {
            lastLifecycleMessage = message
            lastLifecycleMessageAt = now
        }
        val old = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val item = JSONObject().put("id", now * 1000L + old.length()).put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))).put("message", message.replace(Regex("[\\r\\n]+"), " ").take(500))
        val next = JSONArray()
        val start = (old.length() - LIMIT + 1).coerceAtLeast(0)
        for (i in start until old.length()) next.put(old.optJSONObject(i))
        next.put(item)
        prefs.edit().putString(KEY, next.toString()).apply()
    }

    @Synchronized fun read(context: Context): List<AppLogEntry> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            AppLogEntry(item.optLong("id", i.toLong()), item.optString("time"), item.optString("message"))
        }
    }

    @Synchronized fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}