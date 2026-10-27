package com.lab.netlens

object Dpi {
    class TlsInfo(val sni: String?, val alpn: List<String>, val tls13: Boolean, val ech: Boolean, val complete: Boolean, val legacyVer: Int)
    class HttpInfo(val method: String, val host: String?, val path: String, val queryKeys: List<String>, val userAgent: String?)

    private fun u8(b: ByteArray, p: Int): Int = b[p].toInt() and 0xFF
    private fun u16(b: ByteArray, p: Int): Int = (u8(b, p) shl 8) or u8(b, p + 1)

    fun tls(b: ByteArray): TlsInfo? {
        if (b.size < 6 || u8(b, 0) != 0x16 || u8(b, 1) != 3) return null
        if (u8(b, 5) != 1) return null
        var sni: String? = null
        val alpn = ArrayList<String>()
        var t13 = false; var ech = false; var complete = false; var ver = 0
        try {
            var p = 9
            ver = u16(b, p); p += 2 + 32
            p += 1 + u8(b, p); p += 2 + u16(b, p); p += 1 + u8(b, p)
            val extLen = u16(b, p); p += 2
            val end = p + extLen
            while (p + 4 <= end) {
                val type = u16(b, p); val len = u16(b, p + 2); val q = p + 4
                if (type == 0 && len >= 5) { val nl = u16(b, q + 3); sni = String(b, q + 5, nl, Charsets.US_ASCII) }
                else if (type == 16) { var a = q + 2; val ae = q + 2 + u16(b, q); while (a < ae) { val l = u8(b, a); alpn.add(String(b, a + 1, l, Charsets.US_ASCII)); a += 1 + l } }
                else if (type == 43) { var a = q + 1; val ae = q + 1 + u8(b, q); while (a + 1 < ae) { if (u16(b, a) == 0x0304) t13 = true; a += 2 } }
                else if (type == 0xFE0D) ech = true
                p = q + len
            }
            complete = true
        } catch (e: Exception) { complete = false }
        return TlsInfo(sni, alpn, t13, ech, complete, ver)
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