package com.lab.netlens

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.net.DatagramSocket
import java.net.Socket

private class RelayMemoryPreferences : SharedPreferences {
    private val strings = mutableMapOf<String, String?>()
    private val bools = mutableMapOf<String, Boolean>()
    private val longs = mutableMapOf<String, Long>()
    private val sets = mutableMapOf<String, Set<String>?>()
    override fun getString(key: String, defValue: String?): String? = strings[key] ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = bools[key] ?: defValue
    override fun getLong(key: String, defValue: Long): Long = longs[key] ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = sets[key] ?: defValues
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor { strings[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { bools[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { longs[key] = value; return this }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { sets[key] = values; return this }
        override fun apply() {}
    }
}
private class RelayMemoryContext : Context() {
    private val prefs = RelayMemoryPreferences()
    override val filesDir: File = File(System.getProperty("java.io.tmpdir"), "netlens-relay-precheck").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
}

private fun u16(n: Int) = byteArrayOf((n shr 8).toByte(), n.toByte())
private fun ext(type: Int, data: ByteArray) = u16(type) + u16(data.size) + data
private fun clientHello(host: String): ByteArray {
    val name = host.toByteArray(Charsets.US_ASCII)
    val names = byteArrayOf(0) + u16(name.size) + name
    val sni = u16(names.size) + names
    val alpn = byteArrayOf(0, 12, 2) + "h2".toByteArray(Charsets.US_ASCII) + byteArrayOf(8) + "http/1.1".toByteArray(Charsets.US_ASCII)
    val versions = byteArrayOf(2, 3, 4)
    val extensions = ext(0, sni) + ext(16, alpn) + ext(43, versions)
    val body = ByteArrayOutputStream().apply {
        write(byteArrayOf(3, 3)); write(ByteArray(32)); write(byteArrayOf(0))
        write(u16(2)); write(byteArrayOf(0x13, 0x01)); write(byteArrayOf(1, 0))
        write(u16(extensions.size)); write(extensions)
    }.toByteArray()
    val handshake = byteArrayOf(1, 0, ((body.size shr 8) and 0xff).toByte(), (body.size and 0xff).toByte()) + body
    return byteArrayOf(0x16, 0x03, 0x01) + u16(handshake.size) + handshake
}

private class RecordingVpnService : VpnService() {
    var tcpProtectCalls = 0
    var udpProtectCalls = 0
    override fun protect(socket: Socket): Boolean { tcpProtectCalls++; return true }
    override fun protect(socket: DatagramSocket): Boolean { udpProtectCalls++; return true }
}

private fun makeEstablished(conn: TcpConn) {
    val f = TcpConn::class.java.getDeclaredField("established")
    f.isAccessible = true
    f.setBoolean(conn, true)
}
@Suppress("UNCHECKED_CAST")
private fun queue(conn: TcpConn): LinkedBlockingQueue<ByteArray> {
    val f = TcpConn::class.java.getDeclaredField("wq")
    f.isAccessible = true
    return f.get(conn) as LinkedBlockingQueue<ByteArray>
}

fun main() {
    NetworkTools.init(RelayMemoryContext())
    NetworkTools.enabled = true
    NetworkTools.list = emptySet()
    NetworkTools.saveRules("-blocked.example.com")

    val file = File.createTempFile("netlens-relay", ".bin")
    val vpn = RecordingVpnService()
    val engine = Engine(vpn, FileOutputStream(file), PackageManager(), ConnectivityManager())
    try {
        val blockedBytes = clientHello("blocked.example.com")
        val split = 24
        val blockedFlow = Store.newFlow(Store.app("app.blocked", "Blocked app"), "TCP", "203.0.113.8", 443)
        val blocked = TcpConn(engine, "blocked", byteArrayOf(10, 0, 0, 2), byteArrayOf(203.toByte(), 0, 113, 8), 50100, 443, 1000, blockedFlow)
        makeEstablished(blocked)
        blocked.onSegment(Pkt.ACK or Pkt.PSH, 1001, 0, 65535, blockedBytes.copyOfRange(0, split))
        check(queue(blocked).isEmpty()) { "ClientHello parcial no debe salir antes de clasificar" }
        blocked.onSegment(Pkt.ACK or Pkt.PSH, 1001 + split, 0, 65535, blockedBytes.copyOfRange(split, blockedBytes.size))
        check(blockedFlow.notes.any { it.contains("HTTPS bloqueado antes de enviar el ClientHello") })
        check(queue(blocked).isEmpty()) { "ClientHello bloqueado no debe encolarse al relay" }

        val allowedBytes = clientHello("safe.example.com")
        val allowedFlow = Store.newFlow(Store.app("app.allowed", "Allowed app"), "TCP", "203.0.113.9", 443)
        val allowed = TcpConn(engine, "allowed", byteArrayOf(10, 0, 0, 3), byteArrayOf(203.toByte(), 0, 113, 9), 50101, 443, 2000, allowedFlow)
        makeEstablished(allowed)
        allowed.onSegment(Pkt.ACK or Pkt.PSH, 2001, 0, 65535, allowedBytes.copyOfRange(0, split))
        check(queue(allowed).isEmpty()) { "ClientHello incompleto se debe retener" }
        allowed.onSegment(Pkt.ACK or Pkt.PSH, 2001 + split, 0, 65535, allowedBytes.copyOfRange(split, allowedBytes.size))
        val queued = queue(allowed).toList()
        check(queued.size == 1 && queued[0].contentEquals(allowedBytes)) { "ClientHello permitido debe salir completo y en orden" }
        check(allowedFlow.host == "safe.example.com")

        // Cleartext HTTP must remain buffered until Host arrives, then block before relay.
        val http1 = "GET /private HTTP/1.1\r\n".toByteArray(Charsets.US_ASCII)
        val http2 = "Host: blocked.example.com\r\nUser-Agent: precheck\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val httpFlow = Store.newFlow(Store.app("app.http", "HTTP app"), "TCP", "203.0.113.10", 80)
        val httpBlocked = TcpConn(engine, "http-blocked", byteArrayOf(10, 0, 0, 4), byteArrayOf(203.toByte(), 0, 113, 10), 50102, 80, 3000, httpFlow)
        makeEstablished(httpBlocked)
        httpBlocked.onSegment(Pkt.ACK or Pkt.PSH, 3001, 0, 65535, http1)
        check(queue(httpBlocked).isEmpty()) { "HTTP debe esperar la cabecera Host cuando el encabezado aún no terminó" }
        httpBlocked.onSegment(Pkt.ACK or Pkt.PSH, 3001 + http1.size, 0, 65535, http2)
        check(httpFlow.notes.any { it.contains("HTTP bloqueado por Host") })
        check(queue(httpBlocked).isEmpty()) { "Host bloqueado no debe llegar al relay" }

        // HTTP/1.0 may omit Host; a complete header must pass instead of stalling.
        val http10 = "GET / HTTP/1.0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val http10Flow = Store.newFlow(Store.app("app.http10", "HTTP 1.0 app"), "TCP", "203.0.113.11", 80)
        val http10Conn = TcpConn(engine, "http10", byteArrayOf(10, 0, 0, 5), byteArrayOf(203.toByte(), 0, 113, 11), 50103, 80, 4000, http10Flow)
        makeEstablished(http10Conn)
        http10Conn.onSegment(Pkt.ACK or Pkt.PSH, 4001, 0, 65535, http10)
        check(queue(http10Conn).single().contentEquals(http10)) { "HTTP/1.0 completo sin Host debe reenviarse" }
        check(http10Flow.notes.any { it.contains("HTTP sin Host") })

        // Unknown small protocols should be passed rather than held for classification.
        val unknown = "SSH-2.0".toByteArray(Charsets.US_ASCII)
        val unknownFlow = Store.newFlow(Store.app("app.ssh", "SSH app"), "TCP", "203.0.113.12", 22)
        val unknownConn = TcpConn(engine, "unknown", byteArrayOf(10, 0, 0, 6), byteArrayOf(203.toByte(), 0, 113, 12), 50104, 22, 5000, unknownFlow)
        makeEstablished(unknownConn)
        unknownConn.onSegment(Pkt.ACK or Pkt.PSH, 5001, 0, 65535, unknown)
        check(queue(unknownConn).single().contentEquals(unknown)) { "Protocolo no reconocido no debe atascarse" }

        // QUIC-block mode must drop UDP/443 without opening/protecting an upstream socket.
        Store.clear()
        Store.blockQuic = true
        val beforeUdpProtect = vpn.udpProtectCalls
        val quicPacket = Pkt.udp(
            byteArrayOf(10, 0, 0, 7), byteArrayOf(203.toByte(), 0, 113, 13),
            53000, 443, byteArrayOf(1, 2, 3), 0, 3
        )
        engine.handle(quicPacket, quicPacket.size)
        val quicFlow = Store.snapshot().single()
        check(quicFlow.proto == "UDP·QUIC" && quicFlow.closed)
        check(quicFlow.notes.any { it.contains("QUIC/UDP 443 bloqueado") })
        check(vpn.udpProtectCalls == beforeUdpProtect) { "QUIC bloqueado no debe abrir socket de salida" }
        Store.blockQuic = false
        println("PASS — Relay checks: TLS SNI allow/block, HTTP Host split/block, HTTP/1.0 no-Host pass-through, unknown protocol pass-through, QUIC UDP/443 block")
    } finally {
        engine.stop()
        file.delete()
    }
}
