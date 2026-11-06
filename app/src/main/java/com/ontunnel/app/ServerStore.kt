package com.ontunnel.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServerEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val config: String,
    val uri: String = "",
    /** Locked = came from the hidden subscription: details are never shown or editable. */
    val locked: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("config", config).put("uri", uri).put("locked", locked)

    companion object {
        fun fromJson(o: JSONObject): ServerEntry? {
            val config = o.optString("config")
            if (config.isBlank()) return null
            return ServerEntry(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name").ifBlank { "Server" },
                config = config,
                uri = o.optString("uri"),
                locked = o.optBoolean("locked", false)
            )
        }
    }
}

class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("on_tunnel_servers", Context.MODE_PRIVATE)

    var selectedId: String
        get() = prefs.getString("selected_id", "") ?: ""
        set(v) { prefs.edit().putString("selected_id", v).apply() }

    fun load(): List<ServerEntry> {
        prefs.getString("servers_v2", null)?.let { json ->
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { ServerEntry.fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
        }
        return migrateOld()
    }

    fun save(list: List<ServerEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("servers_v2", arr.toString()).apply()
    }

    /** Old format: StringSet of "name|base64(config)|uri|locked". */
    private fun migrateOld(): List<ServerEntry> {
        val old = prefs.getStringSet("items", null) ?: return emptyList()
        val list = old.mapNotNull { raw ->
            try {
                val parts = raw.split("|", limit = 4)
                if (parts.size < 2) return@mapNotNull null
                ServerEntry(
                    name = parts[0].ifBlank { "Server" },
                    config = String(Base64.decode(parts[1], Base64.DEFAULT)),
                    uri = parts.getOrElse(2) { "" },
                    locked = parts.getOrElse(3) { "0" } == "1"
                )
            } catch (_: Exception) { null }
        }
        prefs.getString("selected", null)?.substringBefore('|')?.let { name ->
            list.firstOrNull { it.name == name }?.let { selectedId = it.id }
        }
        save(list)
        return list
    }
}
