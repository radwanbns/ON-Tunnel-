package com.ontunnel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.ontunnel.app.ovpn.TransportProxy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps an SSH session alive and exposes it as a local SOCKS5 port.
 *
 *   server <-- (optional proxy / TLS / payload relay) <-- JSch session <-- SOCKS5 127.0.0.1:port <-- Xray TUN
 *
 * The service lives in the app process, which the VPN excludes (addDisallowedApplication), so the SSH sockets
 * never loop back into the tunnel.
 */
class SshTunnelService : Service() {
    private var proxy: TransportProxy? = null
    private var session: Session? = null
    private var socks: SshSocksServer? = null
    private val main = Handler(Looper.getMainLooper())
    private val rx = AtomicLong()
    private val tx = AtomicLong()
    @Volatile private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (!AppIntegrity.isAppValid(this)) {
            report { onFailed?.invoke("App integrity check failed") }
            stopSelf()
            return START_NOT_STICKY
        }
        val profile = intent.getStringExtra(EXTRA_PROFILE)?.let { SshProfile.fromJson(it) }
        val err = profile?.validate() ?: if (profile == null) "Invalid SSH profile" else null
        if (profile == null || err != null) {
            report { onFailed?.invoke(err ?: "Invalid SSH profile") }
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startForeground(NOTIFICATION_ID, notification())
        val gen = ++generation
        Thread { begin(profile, gen) }.start()
        return START_NOT_STICKY
    }

    private fun begin(profile: SshProfile, gen: Int) {
        try {
            teardown()
            rx.set(0); tx.set(0)

            var host = profile.host.trim()
            var port = profile.port
            val t = profile.toTransport()
            if (t != null) {
                val all = InetAddress.getAllByName(t.dialHost)
                val ordered = all.sortedBy { if (it is Inet4Address) 0 else 1 }.toTypedArray()
                val p = TransportProxy(t, ordered) { msg -> AppLogs.add(this, "SSH relay: ${msg.take(200)}") }
                port = p.start()
                host = "127.0.0.1"
                proxy = p
                AppLogs.add(this, "SSH ${profile.mode}: preparing transport")
                p.prepare()   // payload / proxy / TLS handshake first; the ready session is handed to JSch
                AppLogs.add(this, "SSH ${profile.mode}: transport ready")
            }
            if (gen != generation) return

            val jsch = JSch()
            if (profile.usesKey) {
                jsch.addIdentity(
                    "ontunnel-key",
                    profile.privateKey.trim().replace("\r\n", "\n").plus("\n").toByteArray(),
                    null,
                    profile.passphrase.takeIf { it.isNotEmpty() }?.toByteArray()
                )
            }
            val s = jsch.getSession(profile.username.trim(), host, port)
            if (!profile.usesKey) s.setPassword(profile.password)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setConfig("PreferredAuthentications", if (profile.usesKey) "publickey" else "password,keyboard-interactive")
            // Older tunnel servers (Dropbear...) still use ssh-rsa.
            for (key in listOf("server_host_key", "PubkeyAcceptedAlgorithms")) {
                val cur = s.getConfig(key)
                if (!cur.isNullOrBlank() && !cur.split(",").contains("ssh-rsa")) s.setConfig(key, "$cur,ssh-rsa")
            }
            s.setServerAliveInterval(15000)
            s.setServerAliveCountMax(4)
            AppLogs.add(this, "SSH: authenticating as ${profile.username.trim()}")
            s.connect(30000)
            if (gen != generation) { try { s.disconnect() } catch (_: Exception) { }; return }
            session = s

            val server = SshSocksServer(s, rx, tx)
            val socksPort = server.start()
            socks = server
            AppLogs.add(this, "SSH connected; local SOCKS on 127.0.0.1:$socksPort")

            Thread {
                while (gen == generation && s.isConnected) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                }
                if (gen == generation && running) {
                    AppLogs.add(this, "SSH session closed")
                    report { onDropped?.invoke("SSH connection lost") }
                }
            }.also { it.isDaemon = true }.start()

            report {
                onReady?.invoke(socksPort)
            }
        } catch (e: Exception) {
            if (gen != generation) return
            val msg = friendly(e)
            report { AppLogs.add(this, "SSH error: $msg"); onFailed?.invoke(msg) }
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Auth fail", ignoreCase = true) || m.contains("Auth cancel", ignoreCase = true) ->
                "SSH authentication failed – check the username and password / key"
            m.contains("invalid privatekey", ignoreCase = true) || m.contains("invalid private key", ignoreCase = true) ->
                "The private key is invalid or needs its passphrase"
            m.contains("timeout", ignoreCase = true) -> "SSH connection timed out"
            m.contains("Connection refused", ignoreCase = true) -> "Connection refused by the server"
            else -> m
        }
    }

    private fun teardown() {
        try { socks?.stop() } catch (_: Exception) { }
        socks = null
        try { session?.disconnect() } catch (_: Exception) { }
        session = null
        try { proxy?.stop() } catch (_: Exception) { }
        proxy = null
    }

    private fun report(block: () -> Unit) { main.post(block) }

    override fun onDestroy() {
        running = false
        generation++
        teardown()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val channel = "on_tunnel_ssh"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "ON Tunnel transport", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("ON Tunnel")
            .setContentText("Connected")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.ontunnel.app.SSH_START"
        const val EXTRA_PROFILE = "profile"
        const val NOTIFICATION_ID = 7003

        @Volatile var running = false
        /** Local SOCKS5 port that Xray must use as its outbound. */
        @Volatile var onReady: ((Int) -> Unit)? = null
        @Volatile var onFailed: ((String) -> Unit)? = null
        @Volatile var onDropped: ((String) -> Unit)? = null

        fun start(context: Context, profile: SshProfile) {
            val i = Intent(context, SshTunnelService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE, profile.toJson().toString())
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SshTunnelService::class.java))
        }
    }
}

/** SOCKS5 (CONNECT only, no auth) on loopback; every request becomes an SSH direct-tcpip channel. */
internal class SshSocksServer(
    private val session: Session,
    private val rx: AtomicLong,
    private val tx: AtomicLong
) {
    private val server = ServerSocket(0, 128, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    @Volatile private var active = true

    fun start(): Int {
        Thread {
            while (active) {
                val c = try { server.accept() } catch (_: IOException) { break }
                Thread { handle(c) }.also { it.isDaemon = true }.start()
            }
        }.also { it.isDaemon = true }.start()
        return server.localPort
    }

    fun stop() {
        active = false
        try { server.close() } catch (_: IOException) { }
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun handle(c: Socket) {
        var ch: ChannelDirectTCPIP? = null
        try {
            c.tcpNoDelay = true
            val inp = c.getInputStream()
            val out = c.getOutputStream()

            if (inp.read() != 5) return
            val methods = inp.read()
            if (methods < 0) return
            repeat(methods) { inp.read() }
            out.write(byteArrayOf(5, 0)); out.flush()

            val ver = inp.read(); val cmd = inp.read(); inp.read()
            val atyp = inp.read()
            if (ver != 5) return
            if (cmd != 1) { reply(out, 7); return }                // only CONNECT
            val host = when (atyp) {
                1 -> InetAddress.getByAddress(readN(inp, 4)).hostAddress
                3 -> { val len = inp.read(); String(readN(inp, len), Charsets.ISO_8859_1) }
                4 -> InetAddress.getByAddress(readN(inp, 16)).hostAddress
                else -> { reply(out, 8); return }
            } ?: return
            val port = (inp.read() shl 8) or inp.read()

            val gate = GatedOut(out, rx)
            val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch = channel
            channel.setHost(host)
            channel.setPort(port)
            channel.setInputStream(CountIn(inp, tx), false)
            channel.setOutputStream(gate, false)
            try {
                channel.connect(15000)
            } catch (e: Exception) {
                reply(out, 5)                                        // connection refused
                return
            }
            reply(out, 0)
            gate.release()                                           // remote bytes that arrived early follow the reply

            while (active && !channel.isClosed && !c.isClosed) Thread.sleep(150)
        } catch (_: Exception) {
        } finally {
            try { ch?.disconnect() } catch (_: Exception) { }
            try { c.close() } catch (_: IOException) { }
        }
    }

    private fun readN(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) throw IOException("closed")
            off += r
        }
        return b
    }

    /** Holds remote data back until the SOCKS success reply has been written. */
    private class GatedOut(private val real: OutputStream, private val rx: AtomicLong) : OutputStream() {
        private val lock = Any()
        private var open = false
        private var pending: ByteArrayOutputStream? = ByteArrayOutputStream()

        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(lock) {
                if (!open) {
                    pending?.write(b, off, len)
                } else {
                    real.write(b, off, len)
                    real.flush()
                }
            }
            rx.addAndGet(len.toLong())
        }

        fun release() {
            synchronized(lock) {
                open = true
                val p = pending
                pending = null
                if (p != null && p.size() > 0) {
                    real.write(p.toByteArray())
                    real.flush()
                }
            }
        }

        override fun flush() { synchronized(lock) { if (open) real.flush() } }
        override fun close() { }
    }

    private class CountIn(private val i: InputStream, private val c: AtomicLong) : InputStream() {
        override fun read(): Int = i.read().also { if (it >= 0) c.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            i.read(b, off, len).also { if (it > 0) c.addAndGet(it.toLong()) }
        override fun available(): Int = i.available()
        override fun close() { }
    }
}
