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
    val locked: Boolean = false,
    /** "v2ray" (default, Xray JSON in [config]) or "openvpn" (OvpnProfile JSON in [config]). */
    val type: String = TYPE_V2RAY
) {
    val isOpenVpn: Boolean get() = type == TYPE_OPENVPN

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("config", config).put("uri", uri).put("locked", locked).put("type", type)

    companion object {
        fun fromJson(o: JSONObject): ServerEntry? {
            val config = o.optString("config")
            if (config.isBlank()) return null
            return ServerEntry(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name").ifBlank { "Server" },
                config = config,
                uri = o.optString("uri"),
                locked = o.optBoolean("locked", false),
                type = o.optString("type", TYPE_V2RAY).ifBlank { TYPE_V2RAY }
            )
        }

        const val TYPE_V2RAY = "v2ray"
        const val TYPE_OPENVPN = "openvpn"

        fun openVpn(profile: OvpnProfile, id: String = UUID.randomUUID().toString()) =
            ServerEntry(id = id, name = profile.name, config = profile.toJson().toString(), type = TYPE_OPENVPN)
    }
}

class ServerStore(context: Context) {
    private val prefs = context.getSharedPreferences("on_tunnel_servers", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    var selectedId: String
        get() = prefs.getString("selected_id", "") ?: ""
        set(v) { prefs.edit().putString("selected_id", v).apply() }

    /** "system" | "light" | "dark" */
    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(v) { prefs.edit().putString("theme_mode", v).apply() }

    /** DNS preset: "system" | "cloudflare" | "google" | "adguard" | "custom" */
    var dnsMode: String
        get() = prefs.getString("dns_mode", "system") ?: "system"
        set(v) { prefs.edit().putString("dns_mode", v).apply() }

    var customDns: String
        get() = prefs.getString("custom_dns", "1.1.1.1") ?: "1.1.1.1"
        set(v) { prefs.edit().putString("custom_dns", v).apply() }

    /** Stable hardware ID for this install (generated once). */
    val hwid: String
        get() {
            var id = prefs.getString("hwid", null)
            if (id.isNullOrBlank()) {
                val androidId = try {
                    android.provider.Settings.Secure.getString(
                        appContext.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    ) ?: ""
                } catch (_: Exception) { "" }
                id = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
                    "OT-" + androidId.take(16).uppercase()
                } else {
                    "OT-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
                }
                prefs.edit().putString("hwid", id).apply()
            }
            return id!!
        }

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
