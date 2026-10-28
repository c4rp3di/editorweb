package com.lab.netlens

import java.io.ByteArrayOutputStream
import java.util.Locale

object Dpi {
    class TlsInfo(val sni: String?, val alpn: List<String>, val tls13: Boolean, val ech: Boolean, val complete: Boolean, val legacyVer: Int)
    class HttpInfo(val method: String, val host: String?, val path: String, val queryKeys: List<String>, val userAgent: String?)

    private fun u8(b: ByteArray, p: Int): Int = b[p].toInt() and 0xFF
    private fun u16(b: ByteArray, p: Int): Int = (u8(b, p) shl 8) or u8(b, p + 1)

    /**
     * Parses a TLS ClientHello from captured TCP bytes. The input can contain a
     * fragmented record or a ClientHello split across several handshake records.
     * An incomplete result is deliberately distinct from non-TLS traffic so the
     * caller can wait for more TCP segments instead of permanently missing SNI.
     */
    fun tls(b: ByteArray): TlsInfo? {
        fun incomplete() = TlsInfo(null, emptyList(), false, false, false, 0)
        if (b.isEmpty() || u8(b, 0) != 0x16) return null
        if (b.size < 2) return incomplete()
        if (u8(b, 1) != 3) return null
        if (b.size < 5) return incomplete()

        val handshake = ByteArrayOutputStream()
        var pos = 0
        var records = 0
        while (records < 8) {
            if (pos + 5 > b.size) return incomplete()
            val contentType = u8(b, pos)
            if (contentType != 22 || u8(b, pos + 1) != 3) {
                return if (handshake.size() == 0) null else incomplete()
            }
            val recordLength = u16(b, pos + 3)
            if (recordLength <= 0 || recordLength > 18432) return null
            val recordStart = pos + 5
            val available = (b.size - recordStart).coerceAtLeast(0)
            val copied = minOf(recordLength, available)
            if (copied > 0) handshake.write(b, recordStart, copied)

            val hs = handshake.toByteArray()
            if (hs.isNotEmpty() && u8(hs, 0) != 1) return null // Not ClientHello.
            if (hs.size >= 4) {
                val helloLength = (u8(hs, 1) shl 16) or (u8(hs, 2) shl 8) or u8(hs, 3)
                if (helloLength <= 0 || helloLength > 65535) return incomplete()
                if (hs.size >= 4 + helloLength) {
                    return parseClientHello(hs.copyOfRange(4, 4 + helloLength)) ?: incomplete()
                }
            }
            if (copied < recordLength) return incomplete()
            pos = recordStart + recordLength
            if (pos >= b.size) return incomplete()
            records++
        }
        return incomplete()
    }

    private fun parseClientHello(body: ByteArray): TlsInfo? {
        fun incomplete() = TlsInfo(null, emptyList(), false, false, false, 0)
        fun need(p: Int, n: Int, end: Int = body.size): Boolean = p >= 0 && n >= 0 && p <= end && n <= end - p
        if (!need(0, 35)) return incomplete()
        val legacyVersion = u16(body, 0)
        var p = 34 // legacy_version (2) + random (32)

        val sessionLength = u8(body, p); p += 1
        if (!need(p, sessionLength)) return incomplete()
        p += sessionLength
        if (!need(p, 2)) return incomplete()
        val cipherLength = u16(body, p); p += 2
        if (cipherLength < 2 || cipherLength % 2 != 0 || !need(p, cipherLength)) return incomplete()
        p += cipherLength
        if (!need(p, 1)) return incomplete()
        val compressionLength = u8(body, p); p += 1
        if (compressionLength < 1 || !need(p, compressionLength)) return incomplete()
        p += compressionLength

        // Extensions are optional in older ClientHello messages.
        if (p == body.size) return TlsInfo(null, emptyList(), false, false, true, legacyVersion)
        if (!need(p, 2)) return incomplete()
        val extensionsLength = u16(body, p); p += 2
        if (!need(p, extensionsLength)) return incomplete()
        val extensionsEnd = p + extensionsLength

        var sni: String? = null
        val alpn = ArrayList<String>()
        var tls13 = false
        var ech = false
        while (p < extensionsEnd) {
            if (!need(p, 4, extensionsEnd)) return incomplete()
            val type = u16(body, p)
            val length = u16(body, p + 2)
            val q = p + 4
            if (!need(q, length, extensionsEnd)) return incomplete()
            val end = q + length
            when (type) {
                0 -> { // server_name
                    if (length >= 5 && need(q, 2, end)) {
                        val namesLength = u16(body, q)
                        var a = q + 2
                        val namesEnd = a + namesLength
                        if (namesEnd <= end) {
                            while (a + 3 <= namesEnd) {
                                val nameType = u8(body, a)
                                val nameLength = u16(body, a + 1)
                                a += 3
                                if (nameLength <= 0 || a + nameLength > namesEnd) break
                                if (nameType == 0 && sni == null) {
                                    val candidate = String(body, a, nameLength, Charsets.US_ASCII)
                                        .trimEnd('.').lowercase(Locale.ROOT)
                                    if (validDnsName(candidate)) sni = candidate
                                }
                                a += nameLength
                            }
                        }
                    }
                }
                16 -> { // ALPN
                    if (length >= 3) {
                        val listLength = u16(body, q)
                        var a = q + 2
                        val listEnd = minOf(end, a + listLength)
                        while (a < listEnd) {
                            val n = u8(body, a); a++
                            if (n <= 0 || a + n > listEnd) break
                            alpn.add(String(body, a, n, Charsets.US_ASCII))
                            a += n
                        }
                    }
                }
                43 -> { // supported_versions
                    if (length >= 3) {
                        val listLength = u8(body, q)
                        var a = q + 1
                        val listEnd = minOf(end, a + listLength)
                        while (a + 1 < listEnd) {
                            if (u16(body, a) == 0x0304) tls13 = true
                            a += 2
                        }
                    }
                }
                0xFE0D -> ech = true // Encrypted ClientHello extension.
            }
            p = end
        }
        return TlsInfo(sni, alpn, tls13, ech, true, legacyVersion)
    }

    private fun validDnsName(host: String): Boolean {
        if (host.isEmpty() || host.length > 253 || host.startsWith('.') || host.endsWith('.')) return false
        return host.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        } && host.all { it.code < 128 }
    }

    private val METHODS = listOf("GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ")
    private val HOST_RE = Regex("(?im)^host:\\s*([^\\r\\n]+)")
    private val UA_RE = Regex("(?im)^user-agent:\\s*([^\\r\\n]+)")

    fun http(b: ByteArray): HttpInfo? {
        val head = String(b, 0, minOf(b.size, 2048), Charsets.ISO_8859_1)
        val m = METHODS.firstOrNull { head.startsWith(it) } ?: return null
        var firstEnd = head.indexOf('\r'); if (firstEnd < 0) firstEnd = head.indexOf('\n')
        val line = if (firstEnd > 0) head.substring(0, firstEnd) else head
        val parts = line.split(' ')
        val target = if (parts.size > 1) parts[1] else ""
        val path = target.substringBefore('?')
        val keys = if (target.contains('?')) target.substringAfter('?').split('&').map { it.substringBefore('=') }.filter { it.isNotEmpty() }.take(12) else emptyList()
        val host = HOST_RE.find(head)?.groupValues?.get(1)?.trim()
        val ua = UA_RE.find(head)?.groupValues?.get(1)?.trim()
        return HttpInfo(m.trim(), host, path.take(80), keys, ua?.take(80))
    }

    private val HINTS = listOf("password=", "passwd=", "pwd=", "token=", "api_key=", "apikey=", "access_token=", "authorization:", "email=", "mail=", "imei=", "android_id=", "advertising_id=", "gaid=", "aaid=", "lat=", "lon=", "latitude=", "longitude=", "device_id=", "deviceid=", "phone=")

    fun hints(text: String): List<String> {
        val t = text.lowercase()
        return HINTS.filter { t.contains(it) }.map { it.trimEnd('=').trimEnd(':') }.distinct()
    }

    private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder(); var p = start; var next = -1; var guard = 0
        while (true) {
            guard++; if (guard > 60) throw IllegalStateException("dns loop")
            val l = u8(b, p)
            if (l == 0) { p++; break }
            if ((l and 0xC0) == 0xC0) { if (next < 0) next = p + 2; p = ((l and 0x3F) shl 8) or u8(b, p + 1); continue }
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, p + 1, l, Charsets.US_ASCII)); p += 1 + l
        }
        return Pair(sb.toString(), if (next >= 0) next else p)
    }

    fun dnsQuery(b: ByteArray): String? = try { if (b.size < 13) null else readName(b, 12).first } catch (e: Exception) { null }

    /** 2.4 · Tipo (QTYPE) de la primera pregunta; 0 si no se puede leer. */
    fun dnsQType(q: ByteArray): Int = try {
        if (q.size < 12 || u16(q, 4) != 1) 0 else u16(q, readName(q, 12).second)
    } catch (e: Exception) { 0 }

    /**
     * 2.4 · Sintetiza una respuesta DNS local para consultas bloqueadas o de
     * HTTPS(65)/SVCB(64): para consultas A responde 0.0.0.0 (TTL 300) y para
     * cualquier otro tipo (AAAA, HTTPS, SVCB...) responde NOERROR vacío, para
     * que el resolvedor no espere y decaiga rápido a IPv4 / SNI visible.
     * Devuelve null si la consulta no se puede analizar; el llamador conserva
     * entonces su comportamiento previo (NXDOMAIN sintético).
     */
    fun dnsBlockedReply(q: ByteArray): ByteArray? {
        try {
            if (q.size < 12 || u16(q, 4) != 1) return null
            val name = readName(q, 12)
            val qEnd = name.second + 4
            if (qEnd > q.size) return null
            val isA = u16(q, name.second) == 1
            val out = java.io.ByteArrayOutputStream(qEnd + 16)
            out.write(q, 0, 2)                                   // ID del cliente
            out.write(byteArrayOf(0x84.toByte(), 0x00))          // QR=1, RA=1, rcode 0
            out.write(byteArrayOf(0x00, 0x01))                    // QDCOUNT 1
            out.write(if (isA) byteArrayOf(0x00, 0x01) else byteArrayOf(0x00, 0x00)) // ANCOUNT
            out.write(byteArrayOf(0x00, 0x00))                    // NSCOUNT 0
            out.write(byteArrayOf(0x00, 0x00))                    // ARCOUNT 0 (se omite el EDNS0 del cliente)
            out.write(q, 12, qEnd - 12)                           // pregunta original
            if (isA) out.write(byteArrayOf(                      // puntero C00C · A · IN · TTL 300 · 0.0.0.0
                0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01,
                0x00, 0x00, 0x01, 0x2C, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00))
            return out.toByteArray()
        } catch (e: Exception) { return null }
    }

    fun dnsAnswers(b: ByteArray, n: Int): Pair<String, List<String>>? {
        val d = b.copyOf(n); val ips = ArrayList<String>(); var qname = ""
        try {
            val qd = u16(d, 4); val an = u16(d, 6); var p = 12
            for (i in 0 until qd) { val r = readName(d, p); if (qname.isEmpty()) qname = r.first; p = r.second + 4 }
            for (i in 0 until an) {
                val r = readName(d, p); p = r.second
                val type = u16(d, p); val rdlen = u16(d, p + 8); p += 10
                if (type == 1 && rdlen == 4) ips.add("" + u8(d, p) + "." + u8(d, p + 1) + "." + u8(d, p + 2) + "." + u8(d, p + 3))
                p += rdlen
            }
        } catch (e: Exception) {}
        return if (ips.isEmpty()) null else Pair(qname, ips)
    }

    private val CATS: List<Pair<String, List<String>>> = listOf(
        "Anuncios" to listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "admob.com", "applovin.com", "unityads.unity3d.com", "vungle.com", "adcolony.com", "ironsrc.com", "inmobi.com", "mopub.com", "moloco.com", "pubmatic.com", "criteo.com", "taboola.com", "outbrain.com", "adnxs.com", "rubiconproject.com", "chartboost.com", "smaato.net"),
        "Analítica" to listOf("google-analytics.com", "googletagmanager.com", "app-measurement.com", "appsflyer.com", "adjust.com", "adjust.io", "branch.io", "kochava.com", "mixpanel.com", "amplitude.com", "segment.io", "segment.com", "flurry.com", "singular.net", "braze.com", "onesignal.com", "clevertap.com", "graph.facebook.com", "facebook.net"),
        "Informes de fallos" to listOf("crashlytics.com", "sentry.io", "bugsnag.com", "instabug.com")
    )

    fun category(host: String): String? {
        val h = host.lowercase()
        for (c in CATS) for (s in c.second) if (h == s || h.endsWith("." + s)) return c.first
        return null
    }

    fun inspectOut(flow: Flow, a: ByteArray, port: Int): Boolean {
        val t = tls(a)
        if (t != null) {
            if (!t.complete) return false
            val s = t.sni
            if (s != null) flow.noteHost(s)
            val ver = if (t.tls13) "TLS 1.3" else when (t.legacyVer) { 0x0303 -> "TLS 1.2"; 0x0302 -> "TLS 1.1"; 0x0301 -> "TLS 1.0"; else -> "TLS" }
            flow.notes.add("🔒 " + ver + " cifrado · " + (if (s != null) "SNI: " + s else "sin SNI"))
            if (t.alpn.isNotEmpty()) flow.notes.add("ALPN ofrecido: " + t.alpn.joinToString(", "))
            if (t.ech) flow.notes.add("🕶 ECH presente: el nombre real del sitio puede ir oculto")
            return t.complete
        }
        val h = http(a)
        if (h != null) {
            val hh = h.host
            if (hh != null) flow.noteHost(hh.substringBefore(':'))
            flow.stat.clearFlows.incrementAndGet()
            flow.notes.add("⚠ HTTP SIN CIFRAR: " + h.method + " " + (hh ?: "") + h.path)
            if (h.queryKeys.isNotEmpty()) flow.notes.add("Parámetros enviados (solo nombres): " + h.queryKeys.joinToString(", "))
            val ua = h.userAgent
            if (ua != null) flow.notes.add("User-Agent: " + ua)
            val hs = hints(String(a, 0, minOf(a.size, 4096), Charsets.ISO_8859_1))
            if (hs.isNotEmpty()) {
                flow.notes.add("⚠ Posibles datos sensibles en claro (claves): " + hs.joinToString(", "))
                flow.stat.sensitive.addAll(hs)
            }
            return true
        }
        if (a.size >= 8) {
            flow.notes.add("❓ Protocolo no reconocido (puerto " + port + "): ¿binario o cifrado propio?")
            return true
        }
        return false
    }
}

