package com.ontunnel.app

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Lightweight, best-effort heuristics for a tampered *runtime* environment –
 * complementary to [AppIntegrity] (which only checks the installed APK's
 * identity) and [SecureStrings] (which checks nothing explicitly, it just
 * silently fails to decrypt).
 *
 * None of these signals are proof on their own – rooted devices and some
 * legitimate tools can trip individual checks – so this intentionally
 * separates "hard" signals (an active debugger/instrumentation attached to
 * *this* process right now) from "soft" signals (root present on the
 * device), and only the hard ones are used to refuse to run.
 */
internal object ThreatDetector {

    /** Strong signal: something is actively attached to / instrumenting this
     *  process right now (a debugger, or a Frida-style injector). Safe to
     *  act on. */
    fun hasActiveInstrumentation(context: Context): Boolean {
        if (AppIntegrity.isDebuggerAttached()) return true
        if (hasTracerPid()) return true
        if (hasFridaIndicators()) return true
        return false
    }

    /** A process being ptrace-attached (common to both a JDWP debugger and
     *  Frida's default injection mode) shows up as a non-zero TracerPid in
     *  /proc/self/status. */
    private fun hasTracerPid(): Boolean = try {
        File("/proc/self/status").takeIf { it.canRead() }?.let { f ->
            BufferedReader(FileReader(f)).use { reader ->
                reader.lineSequence().any { line ->
                    line.startsWith("TracerPid:") &&
                        line.removePrefix("TracerPid:").trim().toIntOrNull()?.let { it != 0 } == true
                }
            }
        } ?: false
    } catch (_: Exception) {
        false
    }

    private val FRIDA_PORTS = intArrayOf(27042, 27043)

    private fun hasFridaIndicators(): Boolean {
        // Default frida-server listening port.
        for (port in FRIDA_PORTS) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 50)
                    return true
                }
            } catch (_: Exception) {
                // Not listening on this port – expected in the normal case.
            }
        }
        // Frida's own thread naming convention, visible via /proc/self/task/*/comm
        // on most Android versions without extra permissions.
        return try {
            File("/proc/self/task").listFiles()?.any { taskDir ->
                val comm = File(taskDir, "comm")
                comm.canRead() && comm.readText().contains("frida", ignoreCase = true)
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Soft signal only: root presence. Deliberately NOT used to block
     *  anything by default – many privacy-conscious VPN users legitimately
     *  root their own devices. Exposed for optional telemetry/UI hints. */
    fun hasRootIndicators(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
            "/su/bin/su"
        )
        if (paths.any { File(it).exists() }) return true
        return try {
            Runtime.getRuntime().exec(arrayOf("which", "su")).inputStream
                .bufferedReader().readLine() != null
        } catch (_: Exception) {
            false
        }
    }
}
