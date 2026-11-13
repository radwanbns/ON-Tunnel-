package com.ontunnel.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac

/**
 * Portable ON Tunnel server files.
 * Plain files use JSON; locked files use PBKDF2-HMAC-SHA256 + AES-256-GCM.
 */
object ServerTransfer {
    private const val FORMAT = "ontunnel-servers"
    private const val LOCKED_FORMAT = "ontunnel-servers-locked"
    private const val VERSION = 1
    private const val ITERATIONS = 210_000

    fun export(entries: List<ServerEntry>, password: String? = null): String {
        require(entries.isNotEmpty()) { "There are no servers to export" }
        val payload = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("servers", JSONArray().also { a -> entries.forEach { a.put(it.toJson()) } })
        val plain = payload.toString()
        if (password == null) return plain
        require(password.length >= 6) { "Password must contain at least 6 characters" }

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD("$LOCKED_FORMAT:$VERSION".toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", LOCKED_FORMAT)
            .put("version", VERSION)
            .put("kdf", "PBKDF2-HMAC-SHA256")
            .put("iterations", ITERATIONS)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString()
    }

    fun isLockedFile(text: String): Boolean = runCatching {
        JSONObject(text.trim()).optString("format") == LOCKED_FORMAT
    }.getOrDefault(false)

    fun import(text: String, password: String? = null): List<ServerEntry> {
        val raw = text.trim()
        require(raw.isNotEmpty()) { "File is empty" }
        val root = JSONObject(raw)
        val format = root.optString("format")
        val body = if (format == LOCKED_FORMAT) {
            require(!password.isNullOrEmpty()) { "Password is required for this locked file" }
            val version = root.optInt("version", -1)
            require(version == VERSION) { "Unsupported locked file version" }
            val iterations = root.optInt("iterations", ITERATIONS)
            require(iterations in 100_000..1_000_000) { "Invalid encryption settings" }
            val salt = Base64.decode(root.getString("salt"), Base64.DEFAULT)
            val iv = Base64.decode(root.getString("iv"), Base64.DEFAULT)
            val data = Base64.decode(root.getString("data"), Base64.DEFAULT)
            require(salt.size == 16 && iv.size == 12) { "Invalid locked file" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(password!!, salt), GCMParameterSpec(128, iv))
            cipher.updateAAD("$LOCKED_FORMAT:$VERSION".toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } else raw

        val obj = JSONObject(body)
        val array = obj.optJSONArray("servers")
            ?: (if (obj.has("config") && obj.has("name")) JSONArray().put(obj) else null)
            ?: error("This is not an ON Tunnel server file")
        val result = mutableListOf<ServerEntry>()
        for (i in 0 until array.length()) {
            val source = array.optJSONObject(i) ?: continue
            val entry = ServerEntry.fromJson(source) ?: continue
            // Imported servers are always local/editable, never subscription-locked.
            result += entry.copy(id = java.util.UUID.randomUUID().toString(), locked = false)
        }
        require(result.isNotEmpty()) { "No valid servers found in this file" }
        return result
    }

    /** URI for every engine. V2Ray uses its standard URI; SSH/OpenVPN use portable ON Tunnel URIs. */
    fun shareUri(entry: ServerEntry): String {
        if (entry.type == ServerEntry.TYPE_V2RAY) {
            if (entry.uri.isNotBlank()) return entry.uri
            error("This V2Ray entry has no share URI; share it as JSON")
        }
        val encoded = Base64.encodeToString(
            entry.toJson().toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return when (entry.type) {
            ServerEntry.TYPE_SSH -> "ontunnel+ssh://$encoded"
            ServerEntry.TYPE_OPENVPN -> "ontunnel+openvpn://$encoded"
            else -> error("Unsupported server type")
        }
    }

    fun importText(text: String): List<ServerEntry> {
        val raw = text.trim()
        if (raw.startsWith("ontunnel+ssh://") || raw.startsWith("ontunnel+openvpn://")) {
            val body = raw.substringAfter("://")
            val decoded = String(
                Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
            return import(JSONObject().put("format", FORMAT).put("version", VERSION)
                .put("servers", JSONArray().put(JSONObject(decoded))).toString())
        }
        if (raw.startsWith("{")) {
            val json = JSONObject(raw)
            if (json.has("outbounds")) {
                val parsed = V2RayConfigParser.parse(raw)
                return listOf(ServerEntry(name = parsed.name, config = parsed.config, uri = parsed.uri))
            }
            return import(raw)
        }
        val entries = mutableListOf<ServerEntry>()
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val parsed = V2RayConfigParser.parse(line)
            entries += ServerEntry(name = parsed.name, config = parsed.config, uri = parsed.uri)
        }
        require(entries.isNotEmpty()) { "No supported servers found" }
        return entries
    }

    /** PBKDF2-HMAC-SHA256 implemented with Mac for Android API 24 compatibility. */
    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val block = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, block, 0, salt.size)
        block[block.size - 1] = 1
        var u = mac.doFinal(block)
        val result = u.copyOf()
        for (i in 2..ITERATIONS) {
            u = mac.doFinal(u)
            for (j in result.indices) result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
        }
        return SecretKeySpec(result, "AES")
    }
}
