package com.lab.netlens

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object Pkt {
    const val FIN = 1; const val SYN = 2; const val RST = 4; const val PSH = 8; const val ACK = 16
    private val ipId = AtomicInteger(1)
    fun u8(b: ByteArray, p: Int): Int = b[p].toInt() and 0xFF
    fun u16(b: ByteArray, p: Int): Int = (u8(b, p) shl 8) or u8(b, p + 1)
    fun u32(b: ByteArray, p: Int): Int = (u8(b, p) shl 24) or (u8(b, p + 1) shl 16) or (u8(b, p + 2) shl 8) or u8(b, p + 3)
    fun put16(b: ByteArray, p: Int, v: Int) { b[p] = (v shr 8).toByte(); b[p + 1] = v.toByte() }
    fun put32(b: ByteArray, p: Int, v: Int) { b[p] = (v shr 24).toByte(); b[p + 1] = (v shr 16).toByte(); b[p + 2] = (v shr 8).toByte(); b[p + 3] = v.toByte() }
    fun ipStr(b: ByteArray, p: Int): String = "" + u8(b, p) + "." + u8(b, p + 1) + "." + u8(b, p + 2) + "." + u8(b, p + 3)
    fun checksum(b: ByteArray, off: Int, len: Int, init: Long): Int {
        var sum: Long = init; var i = off; val end = off + len
        while (i + 1 < end) { sum += ((u8(b, i) shl 8) or u8(b, i + 1)).toLong(); i += 2 }
        if (i < end) sum += (u8(b, i) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFFL) + (sum shr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }
    private fun pseudo(src: ByteArray, dst: ByteArray, proto: Int, len: Int): Long {
        var s = 0L
        s += ((u8(src, 0) shl 8) or u8(src, 1)).toLong(); s += ((u8(src, 2) shl 8) or u8(src, 3)).toLong()
        s += ((u8(dst, 0) shl 8) or u8(dst, 1)).toLong(); s += ((u8(dst, 2) shl 8) or u8(dst, 3)).toLong()
        s += proto.toLong(); s += len.toLong(); return s
    }
    private fun ipHeader(p: ByteArray, total: Int, proto: Int, src: ByteArray, dst: ByteArray) {
        p[0] = 0x45.toByte(); p[1] = 0
        put16(p, 2, total); put16(p, 4, ipId.getAndIncrement() and 0xFFFF); put16(p, 6, 0x4000)
        p[8] = 64; p[9] = proto.toByte()
        System.arraycopy(src, 0, p, 12, 4); System.arraycopy(dst, 0, p, 16, 4)
        put16(p, 10, 0); put16(p, 10, checksum(p, 0, 20, 0L))
    }
    fun tcp(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, seq: Int, ack: Int, flags: Int, win: Int, data: ByteArray?, off: Int, len: Int, mss: Int): ByteArray {
        val th = if (mss > 0) 24 else 20; val total = 20 + th + len
        val p = ByteArray(total); ipHeader(p, total, 6, src, dst)
        val t = 20
        put16(p, t, sp); put16(p, t + 2, dp); put32(p, t + 4, seq); put32(p, t + 8, ack)
        p[t + 12] = ((th / 4) shl 4).toByte(); p[t + 13] = flags.toByte(); put16(p, t + 14, win)
        if (mss > 0) { p[t + 20] = 2; p[t + 21] = 4; put16(p, t + 22, mss) }
        if (len > 0 && data != null) System.arraycopy(data, off, p, t + th, len)
        put16(p, t + 16, checksum(p, t, th + len, pseudo(src, dst, 6, th + len)))
        return p
    }
    fun udp(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, data: ByteArray, off: Int, len: Int): ByteArray {
        val total = 20 + 8 + len; val p = ByteArray(total); ipHeader(p, total, 17, src, dst)
        val t = 20; put16(p, t, sp); put16(p, t + 2, dp); put16(p, t + 4, 8 + len)
        System.arraycopy(data, off, p, t + 8, len)
        var c = checksum(p, t, 8 + len, pseudo(src, dst, 17, 8 + len)); if (c == 0) c = 0xFFFF
        put16(p, t + 6, c); return p
    }
}

class TcpConn(
    private val eng: Engine, private val key: String,
    private val cIp: ByteArray, private val sIp: ByteArray,
    private val cPort: Int, private val sPort: Int,
    clientIsn: Int, val flow: Flow, wsScale: Int = -1
) {
    companion object { val FIN_MARK = ByteArray(0); const val MSS = 1400 }
    private val lk = ReentrantLock(); private val cond = lk.newCondition()
    private val iss: Int = Random().nextInt()
    // 2.3.2: factor de escala de ventana (RFC 7323) anunciado por el cliente
    // en su SYN. Sin aplicarlo, la ventana real del cliente se sobreestimaba
    // o subestimaba por 2^n y la lectura del servidor se ralentizaba o se
    // atascaba con flujos largos (páginas grandes).
    private val winShift: Int = if (wsScale in 0..14) wsScale else -1
    private var rcvNxt: Int = clientIsn + 1; private var sndNxt: Int = iss
    private var lastAck: Int = iss; private var cWnd: Int = 65535
    private var established = false; private var finFromClient = false; private var finToClient = false
    @Volatile private var closed = false; @Volatile private var zeroWin = false
    @Volatile var lastActive: Long = System.currentTimeMillis()
    private var sock: Socket? = null
    private val wq = LinkedBlockingQueue<ByteArray>()
    private val queued = AtomicInteger()
    private val dpiBuf = ByteArrayOutputStream()
    private var dpiDone = false
    private var dpiFlushed = false

    fun begin() {
        eng.pool.execute {
            try {
                // 2.3.2: Android crea el descriptor nativo de un Socket de
                // forma perezosa; protect() sobre un socket sin descriptor
                // devuelve false y esa era la causa de que, con la VPN activa,
                // no funcionara ninguna página/app (todas las conexiones se
                // abortaban). Ahora se usa un Socket clásico y se enlaza a un
                // puerto efímero ANTES de protect()/connect(), de modo que el
                // descriptor exista cuando Android intente marcarlo.
                val s = Socket()
                sock = s
                if (!s.isBound) try { s.bind(null) } catch (e: Exception) {
                    DebugLog.log("TCP", "bind previo a protect falló (se continúa): ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
                }
                s.tcpNoDelay = true
                DebugLog.log("TCP", "Conectando upstream ${flow.stat.pkg} ${flow.dstIp}:${sPort} MITM=${Store.mitmEnabled && sPort == 443}")
                if (Store.mitmEnabled && sPort == 443) {
                    // Loopback does not traverse the VPN's default IP route; do not
                    // ask Android to protect this local-only proxy connection.
                    s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), MitmProxy.PORT), 8000)
                } else {
                    eng.protect(s)
                    s.connect(InetSocketAddress(InetAddress.getByAddress(sIp), sPort), 8000)
                }
                DebugLog.log("TCP", "Upstream conectado ${flow.stat.pkg} ${flow.dstIp}:${sPort}")
                lk.withLock {
                    eng.write(Pkt.tcp(sIp, cIp, sPort, cPort, iss, rcvNxt, Pkt.SYN or Pkt.ACK, 65535, null, 0, 0, MSS))
                    sndNxt = iss + 1; lastAck = iss + 1; established = true
                }
                eng.pool.execute { writerLoop(s) }
                readerLoop(s)
            } catch (e: Exception) {
                DebugLog.log("TCP", "ERROR upstream ${flow.stat.pkg} ${flow.dstIp}:${sPort}: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
                if (!closed) { flow.notes.add("✖ No se pudo conectar: " + (e.message ?: e.javaClass.simpleName)); lk.withLock { rst() } }
                teardown()
            }
        }
    }

    private fun advWin(): Int { val q = queued.get(); if (q > 262144) { zeroWin = true; return 0 }; return 65535 }
    private fun sendAck() { eng.write(Pkt.tcp(sIp, cIp, sPort, cPort, sndNxt, rcvNxt, Pkt.ACK, advWin(), null, 0, 0, 0)) }
    private fun rst() { val seq = if (established) sndNxt else 0; eng.write(Pkt.tcp(sIp, cIp, sPort, cPort, seq, rcvNxt, Pkt.RST or Pkt.ACK, 0, null, 0, 0, 0)) }

    fun onSegment(flags: Int, seq: Int, ack: Int, win: Int, data: ByteArray?) {
        lastActive = System.currentTimeMillis()
        if ((flags and Pkt.RST) != 0) { teardown(); return }
        lk.withLock {
            if (!established) return
            if ((flags and Pkt.SYN) != 0) { eng.write(Pkt.tcp(sIp, cIp, sPort, cPort, iss, rcvNxt, Pkt.SYN or Pkt.ACK, 65535, null, 0, 0, MSS)); return }
            if ((flags and Pkt.ACK) != 0) {
                if ((ack - lastAck) > 0) lastAck = ack
                // Aplica el factor de escala de ventana negociado en el SYN.
                cWnd = if (winShift >= 0) minOf(win shl winShift, 1 shl 20) else win
                cond.signalAll()
            }
            val dlen = if (data == null) 0 else data.size
            if (dlen > 0 && data != null) {
                if (seq == rcvNxt) {
                    rcvNxt += dlen
                    // Buffer the initial bytes until classification completes. This
                    // prevents even an early fragment of a blocked ClientHello being
                    // relayed upstream before the SNI rule is evaluated.
                    if (inspect(data)) {
                        flow.closed = true
                        rst()
                        teardown()
                        return
                    }
                    if (dpiDone && !dpiFlushed) {
                        queueUpstream(dpiBuf.toByteArray())
                        dpiFlushed = true
                    } else if (dpiFlushed) {
                        queueUpstream(data)
                    }
                }
                sendAck()
            }
            if ((flags and Pkt.FIN) != 0) {
                if (!finFromClient && (seq + dlen) == rcvNxt) {
                    if (!dpiFlushed) {
                        if (!dpiDone) flow.notes.add("DPI: conexión cerrada antes de clasificar la cabecera; se reenvía sin filtrar por dominio")
                        dpiDone = true
                        queueUpstream(dpiBuf.toByteArray())
                        dpiFlushed = true
                    }
                    finFromClient = true; rcvNxt += 1; wq.add(FIN_MARK)
                }
                sendAck()
            }
            checkDone()
        }
    }

    private fun queueUpstream(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        wq.add(bytes)
        queued.addAndGet(bytes.size)
        flow.addUp(bytes.size)
    }

    private fun indexOfHeaderEnd(data: ByteArray): Int {
        if (data.size < 2) return -1
        for (i in 0 until data.size - 1) {
            if (i + 3 < data.size && data[i] == 13.toByte() && data[i + 1] == 10.toByte() &&
                data[i + 2] == 13.toByte() && data[i + 3] == 10.toByte()) return i + 4
            if (data[i] == 10.toByte() && data[i + 1] == 10.toByte()) return i + 2
        }
        return -1
    }

    /** Returns true when the first request must be blocked before its payload is relayed. */
    private fun inspect(d: ByteArray): Boolean {
        if (dpiDone) return false
        dpiBuf.write(d, 0, d.size)
        if (dpiBuf.size() > 65536) {
            dpiDone = true
            flow.notes.add("DPI: cabecera inicial demasiado grande; no se pudo clasificar")
            return false
        }
        val a = dpiBuf.toByteArray()

        // HTTPS domain filtering: inspect the complete ClientHello before the
        // writer thread is allowed to relay it to the remote socket / TLS proxy.
        val tls = Dpi.tls(a)
        if (tls != null) {
            if (!tls.complete) return false // TCP segment / TLS record is incomplete.
            dpiDone = true
            val host = tls.sni
            if (host != null && NetworkTools.deny(flow.stat.pkg, host)) {
                DebugLog.log("DPI", "BLOQUEO TLS por SNI host=$host app=${flow.stat.pkg}")
                flow.noteHost(host)
                flow.notes.add("⛔ HTTPS bloqueado antes de enviar el ClientHello (SNI): $host")
                NetworkTools.record(flow.stat.pkg)
                return true
            }
            // 2.4 · Anti-ECH estricto: sin ECH el SNI queda visible y filtrable.
            // El sitio reintenta sin ECH (el modo estricto era el plan B de
            // «Bloquear ECH»; el primario es cortar las consultas 65/64).
            if (Store.rejectEch && tls.ech) {
                DebugLog.log("DPI", "ECH rechazado host=${host ?: "oculto"} app=${flow.stat.pkg}")
                flow.notes.add("⛔ ECH rechazado: el SNI debe ir visible para poder filtrarlo")
                NetworkTools.record(flow.stat.pkg)
                return true
            }
            Dpi.inspectOut(flow, a, sPort)
            DebugLog.log("DPI", "TLS clasificado app=${flow.stat.pkg} host=${host ?: "SNI no visible"} destino=${flow.dstIp}:${sPort}")
            if (host == null) flow.notes.add("TLS: SNI no visible; este dominio no se puede filtrar por nombre")
            return false
        }

        // Also apply the same rules to cleartext HTTP Host headers, without
        // marking inspection complete until the Host header has arrived.
        val http = Dpi.http(a)
        if (http != null) {
            val host = http.host?.substringBefore(':')?.trim()?.trimEnd('.')?.lowercase(java.util.Locale.ROOT)
            if (host == null) {
                // HTTP/1.0 permits requests without Host. Wait only while the
                // header block is still incomplete; once complete, pass through
                // rather than holding the stream forever waiting for a header
                // that the protocol does not require.
                val headerEnd = indexOfHeaderEnd(a)
                if (headerEnd < 0) return false
                dpiDone = true
                flow.notes.add("HTTP sin Host; se reenvía sin filtro por dominio")
                Dpi.inspectOut(flow, a, sPort)
                return false
            }
            if (NetworkTools.deny(flow.stat.pkg, host)) {
                DebugLog.log("DPI", "BLOQUEO HTTP por Host host=$host app=${flow.stat.pkg}")
                flow.noteHost(host)
                flow.notes.add("⛔ HTTP bloqueado por Host: $host")
                NetworkTools.record(flow.stat.pkg)
                return true
            }
            dpiDone = true
            Dpi.inspectOut(flow, a, sPort)
            return false
        }

        // Do not hold arbitrary TCP protocols waiting for the DPI classifier.
        // Only a possible partial HTTP method is worth buffering; TLS candidates
        // are handled above by Dpi.tls(). Unknown protocols are passed through.
        val prefix = String(a, 0, minOf(a.size, 16), Charsets.US_ASCII)
        val methods = listOf("GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ")
        val possiblePartialHttp = methods.any { it.startsWith(prefix) } &&
            methods.none { prefix.startsWith(it) }
        if (possiblePartialHttp) return false

        dpiDone = true
        Dpi.inspectOut(flow, a, sPort)
        if (a.size < 8) flow.notes.add("DPI: protocolo no reconocido; se reenvía sin bloquear")
        return false
    }

    private fun checkDone() { if (finFromClient && finToClient) eng.timer.schedule(Runnable { teardown() }, 3, TimeUnit.SECONDS) }

    private fun writerLoop(s: Socket) {
        try {
            val os = s.getOutputStream()
            while (!closed) {
                val d = wq.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (d === FIN_MARK) { try { s.shutdownOutput() } catch (e: Exception) {}; continue }
                os.write(d); os.flush()
                val q = queued.addAndGet(-d.size)
                if (zeroWin && q < 131072) lk.withLock { zeroWin = false; sendAck() }
                lastActive = System.currentTimeMillis()
            }
        } catch (e: Exception) { if (!closed) { lk.withLock { rst() }; teardown() } }
    }

    private fun readerLoop(s: Socket) {
        val buf = ByteArray(MSS)
        try {
            val ins = s.getInputStream()
            while (!closed) {
                val n = ins.read(buf)
                if (n < 0) break
                if (n == 0) continue
                lk.withLock {
                    while (!closed && (sndNxt - lastAck) > 0 && (sndNxt - lastAck) + n > cWnd) cond.await(200, TimeUnit.MILLISECONDS)
                    if (!closed) { eng.write(Pkt.tcp(sIp, cIp, sPort, cPort, sndNxt, rcvNxt, Pkt.ACK or Pkt.PSH, advWin(), buf, 0, n, 0)); sndNxt += n }
                }
                flow.addDown(n); lastActive = System.currentTimeMillis()
            }
            lk.withLock {
                if (!closed) { eng.write(Pkt.tcp(sIp, cIp, sPort, cPort, sndNxt, rcvNxt, Pkt.FIN or Pkt.ACK, advWin(), null, 0, 0, 0)); sndNxt += 1; finToClient = true; checkDone() }
            }
        } catch (e: Exception) { if (!closed) { lk.withLock { rst() }; teardown() } }
    }

    fun teardown() {
        if (closed) return
        closed = true
        try { sock?.close() } catch (e: Exception) {}
        flow.closed = true; eng.removeTcp(key); lk.withLock { cond.signalAll() }
    }
}

class UdpCtx(val sock: DatagramSocket?, val flow: Flow)

class Engine(
    private val svc: VpnService, private val out: FileOutputStream,
    private val pm: PackageManager, private val cm: ConnectivityManager
) {
    val pool = Executors.newCachedThreadPool()
    val timer = Executors.newScheduledThreadPool(1)
    private val tcp = ConcurrentHashMap<String, TcpConn>()
    private val udp = ConcurrentHashMap<String, UdpCtx>()
    private val uidCache = ConcurrentHashMap<Int, AppStat>()
    @Volatile private var alive = true
    @Volatile private var nonV4Seen = false

    /** 2.4 · Aviso único: el motor solo procesa IPv4; lo demás se descarta. */
    private fun noteNonV4Seen() {
        if (!nonV4Seen) {
            nonV4Seen = true
            DebugLog.log("VPN", "Paquete no IPv4 recibido por el TUN: se descarta (motor solo IPv4). Apps dual-stack: decaerán a IPv4; destinos solo-v6 quedan inaccesibles mientras dure la captura.")
        }
    }

    init { timer.scheduleWithFixedDelay({ janitor() }, 20, 20, TimeUnit.SECONDS) }
    // 2.3.2 · protect() en capas, sin romper el tráfico:
    //  1) Android crea el descriptor nativo de un Socket de forma perezosa,
    //     y protect() sobre un socket sin descriptor devuelve false (por eso
    //     2.2/2.3 abortaba todas las conexiones con la VPN activa). El socket
    //     se enlaza antes de protegerse para garantizar el descriptor.
    //  2) Se reintenta unas veces: el binder puede fallar de forma transitoria.
    //  3) Si aun así no aplica, NO se aborta la conexión: el ámbito de la VPN
    //     (addAllowedApplication/addDisallowedApplication, verificado al
    //     iniciar y con error visible si falla) ya excluye al propio NetLens
    //     del TUN, por lo que un socket sin marcar no puede recursar hacia el
    //     túnel. El aviso queda en el log de depuración.
    private fun tryProtect(kind: String, attempt: () -> Boolean): Boolean {
        for (i in 1..3) {
            try { if (attempt()) { if (i > 1) DebugLog.log("SOCKET", "protect($kind) OK en intento $i"); return true } }
            catch (e: Exception) {
                DebugLog.log("SOCKET", "protect($kind) lanzó ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"} (intento $i)")
            }
            try { Thread.sleep(50L * i) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        return false
    }

    fun protect(s: Socket): Boolean {
        if (!s.isBound) try { s.bind(null) } catch (e: Exception) {
            DebugLog.log("SOCKET", "bind previo a protect(TCP) falló: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
        }
        val ok = tryProtect("TCP") { svc.protect(s) }
        if (!ok) {
            DebugLog.log("SOCKET", "AVISO: protect(TCP) no aplicó; se continúa porque el ámbito de la VPN ya excluye a NetLens del TUN (sin riesgo de bucle).")
        }
        return ok
    }

    fun protect(s: DatagramSocket): Boolean {
        if (!s.isBound) try { s.bind(null) } catch (e: Exception) {
            DebugLog.log("SOCKET", "bind previo a protect(UDP) falló: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
        }
        val ok = tryProtect("UDP") { svc.protect(s) }
        if (!ok) {
            DebugLog.log("SOCKET", "AVISO: protect(UDP) no aplicó; se continúa porque el ámbito de la VPN ya excluye a NetLens del TUN (sin riesgo de bucle).")
        }
        return ok
    }
    fun write(p: ByteArray) { synchronized(out) { try { out.write(p) } catch (e: Exception) {} } }
    fun removeTcp(key: String) { tcp.remove(key) }

    private fun janitor() {
        val now = System.currentTimeMillis()
        for (c in tcp.values) if (now - c.lastActive > 180000) c.teardown()
        for (e in udp.entries) if (e.value.sock == null && now - e.value.flow.last > 60000) udp.remove(e.key)
    }

    fun stop() {
        alive = false
        for (c in tcp.values) c.teardown()
        for (u in udp.values) { try { u.sock?.close() } catch (e: Exception) {} }
        pool.shutdownNow(); timer.shutdownNow()
    }

    fun handle(b: ByteArray, n: Int) {
        if (n < 20) return
        if (((b[0].toInt() shr 4) and 0xF) != 4) { noteNonV4Seen(); return }
        val ihl = (Pkt.u8(b, 0) and 0xF) * 4
        if (ihl < 20 || n < ihl) return
        val total = Pkt.u16(b, 2)
        val len = if (total in 20..n) total else n
        if ((Pkt.u16(b, 6) and 0x3FFF) != 0) return
        when (Pkt.u8(b, 9)) { 6 -> handleTcp(b, len, ihl); 17 -> handleUdp(b, len, ihl) }
    }

    private fun ownerUid(proto: Int, src: ByteArray, sp: Int, dst: ByteArray, dp: Int): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return try { cm.getConnectionOwnerUid(proto, InetSocketAddress(InetAddress.getByAddress(src), sp), InetSocketAddress(InetAddress.getByAddress(dst), dp)) } catch (e: Exception) { -1 }
    }

    /**
     * 2.3.2 · Lee la opción window scale (RFC 7323, kind 3) del SYN del
     * cliente. Devuelve -1 si no la anuncia o si las opciones están mal
     * formadas. Sin esto, cWnd usaba la ventana "en bruto" de los segmentos
     * del cliente, que tras el handshake va multiplicada por 2^escala.
     */
    private fun synWindowScale(b: ByteArray, t: Int, doff: Int): Int {
        var p = t + 20
        val end = t + doff
        while (p < end) {
            val kind = Pkt.u8(b, p)
            if (kind == 0) break            // End of Option List
            if (kind == 1) { p += 1; continue } // No-Op
            if (p + 1 >= end) break
            val len = Pkt.u8(b, p + 1)
            if (len < 2 || p + len > end) break
            if (kind == 3 && len >= 3) return Pkt.u8(b, p + 2)
            p += len
        }
        return -1
    }

    private fun appFor(proto: Int, src: ByteArray, sp: Int, dst: ByteArray, dp: Int): AppStat {
        val uid = ownerUid(proto, src, sp, dst, dp)
        return uidCache.getOrPut(uid) {
            val pkgs = if (uid >= 0) pm.getPackagesForUid(uid) else null
            val pkg = if (pkgs != null && pkgs.isNotEmpty()) pkgs[0] else if (uid == 1000) "android" else "uid:" + uid
            val label = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }
            catch (e: Exception) { if (uid == 1000) "Sistema Android" else if (uid < 0) "App desconocida" else pkg }
            Store.app(pkg, label)
        }
    }

    private fun handleTcp(b: ByteArray, len: Int, ihl: Int) {
        if (len < ihl + 20) return
        val t = ihl
        val sp = Pkt.u16(b, t); val dp = Pkt.u16(b, t + 2)
        val seq = Pkt.u32(b, t + 4); val ack = Pkt.u32(b, t + 8)
        val doff = (Pkt.u8(b, t + 12) shr 4) * 4
        val flags = Pkt.u8(b, t + 13); val win = Pkt.u16(b, t + 14)
        if (doff < 20 || len < t + doff) return
        val dlen = len - t - doff
        val key = "" + sp + ">" + Pkt.ipStr(b, 16) + ":" + dp
        val c = tcp[key]
        val isSyn = (flags and Pkt.SYN) != 0; val isAck = (flags and Pkt.ACK) != 0; val isRst = (flags and Pkt.RST) != 0
        if (c == null) {
            if (isSyn && !isAck) {
                val src = b.copyOfRange(12, 16); val dst = b.copyOfRange(16, 20)
                val stat = appFor(6, src, sp, dst, dp)
                val flow = Store.newFlow(stat, "TCP", Pkt.ipStr(b, 16), dp)
                DebugLog.log("TCP", "SYN app=${stat.pkg} destino=${Pkt.ipStr(b,16)}:$dp")
                if(NetworkTools.deny(stat.pkg, Store.dns[Pkt.ipStr(b,16)])) {
                    DebugLog.log("REGLAS", "BLOQUEO TCP por IP/DNS app=${stat.pkg} destino=${Pkt.ipStr(b,16)}:$dp")
                    NetworkTools.record(stat.pkg);flow.notes.add("Bloqueado por reglas");flow.closed=true
                    write(Pkt.tcp(dst,src,dp,sp,0,seq+1,Pkt.RST or Pkt.ACK,0,null,0,0,0));return
                }
                // 2.4 · Anti-DoH: DoT directo (TCP 853) se rechaza con RST
                // para que la app decaiga rápido al DNS del sistema (via VPN).
                if (Store.blockDoh && dp == 853) {
                    DebugLog.log("REGLAS", "BLOQUEO DoT 853 app=${stat.pkg} destino=${Pkt.ipStr(b,16)}:$dp")
                    NetworkTools.record(stat.pkg);flow.notes.add("⛔ DoT (853) bloqueado: se fuerza el DNS del sistema (via VPN)");flow.closed=true
                    write(Pkt.tcp(dst,src,dp,sp,0,seq+1,Pkt.RST or Pkt.ACK,0,null,0,0,0));return
                }
                val conn = TcpConn(this, key, src, dst, sp, dp, seq, flow, synWindowScale(b, t, doff))
                tcp[key] = conn; conn.begin()
            } else if (!isRst) {
                val src = b.copyOfRange(16, 20); val dst = b.copyOfRange(12, 16)
                if (isAck) write(Pkt.tcp(src, dst, dp, sp, ack, 0, Pkt.RST, 0, null, 0, 0, 0))
                else {
                    val add = dlen + (if (isSyn) 1 else 0) + (if ((flags and Pkt.FIN) != 0) 1 else 0)
                    write(Pkt.tcp(src, dst, dp, sp, 0, seq + add, Pkt.RST or Pkt.ACK, 0, null, 0, 0, 0))
                }
            }
            return
        }
        val data = if (dlen > 0) b.copyOfRange(t + doff, t + doff + dlen) else null
        c.onSegment(flags, seq, ack, win, data)
    }

    private fun handleUdp(b: ByteArray, len: Int, ihl: Int) {
        if (len < ihl + 8) return
        val sp = Pkt.u16(b, ihl); val dp = Pkt.u16(b, ihl + 2)
        val ulen = Pkt.u16(b, ihl + 4)
        val dlen = minOf(ulen - 8, len - ihl - 8)
        if (dlen < 0) return
        if (Pkt.u8(b, 16) >= 224) return
        val key = "" + sp + ">" + Pkt.ipStr(b, 16) + ":" + dp
        val src = b.copyOfRange(12, 16); val dst = b.copyOfRange(16, 20)
        val payloadEarly = b.copyOfRange(ihl + 8, ihl + 8 + dlen)
        val pkgStat=appFor(17,src,sp,dst,dp)
        val hostEarly=if(dp==53) Dpi.dnsQuery(payloadEarly) else Store.dns[Pkt.ipStr(b,16)]
        // 2.4 · Anti-DoH: UDP 853 (DoT) se descarta para no bypassar la VPN.
        if (Store.blockDoh && dp == 853) { DebugLog.log("REGLAS", "UDP 853 (DoT) descartado app=${pkgStat.pkg} destino=${Pkt.ipStr(b,16)}"); return }
        if(NetworkTools.deny(pkgStat.pkg,hostEarly)) {
            NetworkTools.record(pkgStat.pkg)
            if(dp==53 && payloadEarly.size>=12){
                // 2.4 · A -> 0.0.0.0 (fallo rápido); resto -> NOERROR vacío.
                val ok=Dpi.dnsBlockedReply(payloadEarly)
                if(ok!=null){ DebugLog.log("REGLAS","DNS bloqueado (0.0.0.0/vacía) host=${hostEarly ?: "?"} app=${pkgStat.pkg}"); write(Pkt.udp(dst,src,dp,sp,ok,0,ok.size)) }
                else { val reply=payloadEarly.copyOf();reply[2]=((reply[2].toInt() and 1) or 128).toByte();reply[3]=0x83.toByte()
                    for(p in 6..11)reply[p]=0
                    write(Pkt.udp(dst,src,dp,sp,reply,0,reply.size)) }
            };return
        }
        // 2.4 · Anti-ECH: HTTPS(65)/SVCB(64) entregan la configuración ECH.
        // Responder NOERROR vacío evita que el ClientHello la lleve y el SNI
        // quede visible (filtrable). Las apps reintentan como en DNS clásico.
        if (dp == 53 && Store.rejectEch && payloadEarly.size >= 12) {
            val qt = Dpi.dnsQType(payloadEarly)
            if (qt == 64 || qt == 65) {
                Dpi.dnsBlockedReply(payloadEarly)?.let { reply -> write(Pkt.udp(dst,src,dp,sp,reply,0,reply.size)); return }
            }
        }
        if(dp==53 && NetworkTools.dnsMode!="normal") {
            val flow=Store.newFlow(pkgStat,"DNS·"+NetworkTools.dnsMode,Pkt.ipStr(b,16),dp)
            hostEarly?.let{flow.noteHost(it)}
            pool.execute {
                try {
                    val reply=NetworkTools.encryptedDns(payloadEarly){s->protect(s)}
                    flow.addUp(payloadEarly.size);flow.addDown(reply.size)
                    Dpi.dnsAnswers(reply,reply.size)?.let{r->for(ip in r.second)Store.dns[ip]=r.first}
                    write(Pkt.udp(dst,src,dp,sp,reply,0,reply.size))
                }catch(ex:Exception){NetworkTools.errors.incrementAndGet();flow.notes.add("DNS cifrado falló; sin fallback: "+(ex.message ?: ex.javaClass.simpleName))}
                finally{flow.closed=true}
            };return
        }
        val ctx: UdpCtx = udp[key] ?: (createUdp(key, src, dst, sp, dp, Pkt.ipStr(b, 16)) ?: return)
        val sock = ctx.sock
        if (sock == null) { ctx.flow.last = System.currentTimeMillis(); return }
        val payload = b.copyOfRange(ihl + 8, ihl + 8 + dlen)
        if (dp == 53) { val q = Dpi.dnsQuery(payload); if (q != null) { ctx.flow.noteHost(q); ctx.flow.notes.add("DNS: la app pregunta por " + q) } }
        try { sock.send(DatagramPacket(payload, payload.size)); ctx.flow.addUp(dlen) } catch (e: Exception) {}
    }

    private fun createUdp(key: String, src: ByteArray, dst: ByteArray, sp: Int, dp: Int, dstStr: String): UdpCtx? {
        val stat = appFor(17, src, sp, dst, dp)
        val proto = if (dp == 53) "DNS" else if (dp == 443) "UDP·QUIC" else "UDP"
        val flow = Store.newFlow(stat, proto, dstStr, dp)
        if (dp == 443 && Store.blockQuic) { DebugLog.log("QUIC", "BLOQUEADO UDP/443 app=${stat.pkg} destino=$dstStr:$dp"); flow.notes.add("⛔ QUIC/UDP 443 bloqueado; la app puede intentar TCP+TLS, pero el fallback depende de la app"); flow.closed = true; udp[key] = UdpCtx(null, flow); return udp[key] }
        val c: UdpCtx = try {
            // 2.3.2: DatagramSocket() crea el descriptor nativo en el
            // constructor (a diferencia de Socket), así que protect() tiene
            // un descriptor real que marcar antes de connect().
            val s = DatagramSocket()
            protect(s)
            s.connect(InetAddress.getByAddress(dst), dp)
            s.soTimeout = if (dp == 53) 5000 else 30000
            UdpCtx(s, flow)
        } catch (e: Exception) {
            DebugLog.log("UDP", "ERROR app=${stat.pkg} destino=$dstStr:$dp ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
            flow.notes.add("✖ No se pudo abrir el socket UDP: " + (e.message ?: e.javaClass.simpleName))
            flow.closed = true
            return null
        }
        udp[key] = c
        DebugLog.log("UDP", "Socket UDP conectado app=${stat.pkg} destino=$dstStr:$dp")
        if (dp == 443) flow.notes.add("QUIC/HTTP3: va cifrado. Activa «Bloquear QUIC» para forzar TCP+TLS")
        pool.execute { udpReceiver(key, c, src, dst, sp, dp) }
        return c
    }

    private fun udpReceiver(key: String, ctx: UdpCtx, src: ByteArray, dst: ByteArray, sp: Int, dp: Int) {
        val s = ctx.sock ?: return
        val buf = ByteArray(4096)
        try {
            while (alive) {
                val dpk = DatagramPacket(buf, buf.size); s.receive(dpk)
                val n = dpk.length; ctx.flow.addDown(n)
                if (dp == 53) { val r = Dpi.dnsAnswers(buf, n); if (r != null) { for (ip in r.second) Store.dns[ip] = r.first; ctx.flow.notes.add("DNS respuesta: " + r.first + " → " + r.second.joinToString(", ")) } }
                if (n <= 1472) write(Pkt.udp(dst, src, dp, sp, buf, 0, n))
                // 2.3.2: DNS ya no corta tras la primera respuesta. El resolvedor
                // de Android lanza en paralelo (A y AAAA) desde el MISMO puerto,
                // y cortar aquí descartaba respuestas legítimas (la página "no
                // funcionaba"). Se escucha hasta el timeout de inactividad
                // (soTimeout), que cierra el socket en el finally.
            }
        } catch (e: SocketTimeoutException) {} catch (e: Exception) {} finally {
            try { s.close() } catch (e: Exception) {}
            ctx.flow.closed = true; udp.remove(key)
        }
    }
}

