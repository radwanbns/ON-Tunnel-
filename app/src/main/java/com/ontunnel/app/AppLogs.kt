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

    @Synchronized fun add(context: Context, message: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        val item = JSONObject().put("id", now).put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))).put("message", message.replace(Regex("[\\r\\n]+"), " ").take(500))
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