package com.lab.netlens

import android.content.Context
import android.content.SharedPreferences
import java.net.URL
import java.net.Socket
import java.net.InetSocketAddress
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Reglas de dominio, lista de suscripción HTTPS, DNS cifrado (DoH/DoT) y
 * estadísticas de bloqueo. Toda la configuración persiste en
 * SharedPreferences ("netlens_tools"); la lista de dominios se guarda en
 * filesDir/domains.txt con escritura atómica (tmp + rename).
 *
 * 2.3.2: se añaden autoUpdate/savedUrl/lastUpdated, que el resto de la app
 * (Herramientas → Actualización periódica y ListUpdateWorker) ya usaba pero
 * no estaban definidos, y el socket DoT se crea y enlaza antes de pedir
 * protect(), porque Android crea el descriptor nativo de un Socket de forma
 * perezosa y protect() sobre un socket sin descriptor devuelve false.
 *
 * 2.4.0: lista recomendada por defecto (OISD small, formato hosts, ~17k
 * dominios), bloqueo de resolutores DoH/DoT conocidos (anti-bypass del DNS
 * de la VPN) y límite de descarga elevado a 8 MiB para listas grandes.
 */
object NetworkTools {
    private lateinit var prefs: SharedPreferences
    @Volatile var rules: Set<String> = emptySet()
    @Volatile var list: Set<String> = emptySet()
    private lateinit var ctx: Context
    val blocked = AtomicLong()
    val errors = AtomicLong()
    val byApp = ConcurrentHashMap<String, AtomicLong>()

    /** Lista recomendada: OISD small en formato hosts (anuncios/rastreadores, pocos falsos positivos). */
    const val DEFAULT_LIST_URL = "https://small.oisd.nl/hosts"

    /**
     * 2.4 · Servidores DoH/DoT conocidos. Si una app resuelve por aquí, se
     * salta el DNS de la VPN y con él todo el filtrado por nombre. Se bloquean
     * por dominio (DNS/SNI/Host); el puerto 853 se bloquea en el motor. El
     * DoT/DoH interno de NetLens va con protect() y no se ve afectado.
     */
    private val DOH = setOf(
        "dns.google", "dns.google.com", "cloudflare-dns.com", "one.one.one.one",
        "chrome.cloudflare-dns.com", "security.cloudflare-dns.com", "mozilla.cloudflare-dns.com",
        "dns.quad9.net", "dns9.quad9.net", "dns11.quad9.net", "dns.adguard.com", "dns.adguard-dns.com",
        "doh.opendns.com", "doh.familyshield.opendns.com", "dns.sb", "doh.dns.sb",
        "doh.mullvad.net", "doh.cleanbrowsing.org", "dns.surfshark.com", "dnsfast.doh.surfshark.com"
    )

    fun init(c: Context) {
        ctx = c.applicationContext
        prefs = ctx.getSharedPreferences("netlens_tools", 0)
        rules = prefs.getStringSet("rules", emptySet())!!.toSet()
        val file = java.io.File(ctx.filesDir, "domains.txt")
        list = if (file.exists()) file.readLines().toSet() else emptySet()
    }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }

    var dnsMode: String
        get() = prefs.getString("dns", "normal")!!
        set(v) { prefs.edit().putString("dns", v).apply() }

    /** Actualización periódica de la lista (cada ~24 h; WorkManager decide el momento). */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", false)
        set(v) { prefs.edit().putBoolean("autoUpdate", v).apply() }

    /** URL de la última suscripción descargada con éxito; null si nunca hubo una. */
    val savedUrl: String? get() = prefs.getString("url", null)

    /** Marca de tiempo de la última descarga correcta de la lista; 0 si nunca. */
    val lastUpdated: Long get() = prefs.getLong("updated", 0L)

    fun saveRules(text: String) {
        val n = text.lines().map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        require(n.all { Regex("[+-]([^| ]+\\|)?[a-z0-9.-]+").matches(it) }) { "Usa -dominio.com, +dominio.com o -paquete|dominio.com" }
        prefs.edit().putStringSet("rules", n).apply(); rules = n
    }

    private fun matches(h: String, d: String) = h == d || h.endsWith("." + d)

    fun deny(pkg: String, h0: String?): Boolean {
        if (h0 == null) return false
        val h = h0.lowercase(java.util.Locale.ROOT).trimEnd('.')
        // 2.4 · Anti-DoH: bloquea resolutores cifrados conocidos aunque la
        // protección general esté apagada; fuerza el DNS del sistema (por la VPN).
        if (Store.blockDoh && DOH.any { h == it || h.endsWith("." + it) }) return true
        if (!enabled) return false
        val applicable = rules.filter { !it.contains('|') || it.drop(1).substringBefore('|') == pkg }
        if (applicable.any { it.startsWith("+") && matches(h, it.drop(1).substringAfter('|')) }) return false
        if (applicable.any { it.startsWith("-") && matches(h, it.drop(1).substringAfter('|')) }) return true
        var suffix = h
        while (true) { if (list.contains(suffix)) return true; if (!suffix.contains('.')) break; suffix = suffix.substringAfter('.') }
        return Dpi.category(h)?.let { !it.contains("fallos", true) } ?: false
    }

    fun record(pkg: String) { blocked.incrementAndGet(); byApp.getOrPut(pkg) { AtomicLong() }.incrementAndGet() }

    fun updateList(url: String): String {
        val u = URL(url); require(u.protocol == "https") { "Solo HTTPS" }
        val c = u.openConnection() as HttpsURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 10000; c.readTimeout = 15000
        try {
            require(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val bytes = c.inputStream.use { it.readBytesLimited(8 * 1024 * 1024) }
            val domains = bytes.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
                val t = line.substringBefore('#').trim().lowercase(java.util.Locale.ROOT)
                if (t.isEmpty() || t.startsWith("!") || t.startsWith("[")) null
                else {
                    val parts = t.split(Regex("\\s+"))
                    val d = if (parts.size == 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else t
                    d.takeIf { Regex("[a-z0-9-]+(\\.[a-z0-9-]+)+").matches(it) && it != "localhost" && !it.all { x -> x.isDigit() || x == '.' } }
                }
            }.toSet()
            require(domains.isNotEmpty()) { "Sin dominios compatibles; no admite EasyList completa" }
            val target = java.io.File(ctx.filesDir, "domains.txt")
            val tmp = java.io.File(ctx.filesDir, "domains.tmp"); tmp.writeText(domains.joinToString("\n"))
            require(tmp.renameTo(target)) { "No se pudo guardar" }; list = domains
            prefs.edit().putString("url", url).putLong("updated", System.currentTimeMillis()).apply()
            return "${domains.size} dominios guardados. Actualización manual."
        } finally { c.disconnect() }
    }

    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192)
        while (true) { val n = read(b); if (n < 0) break; require(out.size() + n <= max) { "Respuesta demasiado grande" }; out.write(b, 0, n) }
        return out.toByteArray()
    }

    /**
     * Resuelve una consulta DNS en bruto usando DoT (1.1.1.1:853) o DoH
     * (POST application/dns-message). Sin fallback silencioso al DNS en
     * claro: si falla, lanza y el llamador registra el error.
     * El socket DoT se enlaza antes de protect() para que exista el
     * descriptor nativo; el lambda de protección es el del motor.
     */
    fun encryptedDns(q: ByteArray, protect: (Socket) -> Unit): ByteArray {
        val result = if (dnsMode == "DoT") {
            val raw = Socket()
            try { if (!raw.isBound) raw.bind(null) } catch (_: Exception) {}
            protect(raw)
            try {
                raw.connect(InetSocketAddress("1.1.1.1", 853), 8000)
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, "cloudflare-dns.com", 853, true) as SSLSocket
                tls.use {
                    it.soTimeout = 8000
                    val p = it.sslParameters; p.endpointIdentificationAlgorithm = "HTTPS"; it.sslParameters = p; it.startHandshake()
                    val out = DataOutputStream(it.outputStream); out.writeShort(q.size); out.write(q); out.flush()
                    val input = DataInputStream(it.inputStream); val size = input.readUnsignedShort(); require(size >= 12)
                    ByteArray(size).also { answer -> input.readFully(answer) }
                }
            } finally { raw.close() }
        } else {
            val c = URL("https://1.1.1.1/dns-query").openConnection() as HttpsURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 8000; c.readTimeout = 8000
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/dns-message"); c.setRequestProperty("Accept", "application/dns-message")
            try { c.outputStream.use { it.write(q) }; require(c.responseCode == 200); c.inputStream.use { it.readBytesLimited(65535) } } finally { c.disconnect() }
        }
        require(result.size >= 12 && result[0] == q[0] && result[1] == q[1] && (result[2].toInt() and 128) != 0) { "Respuesta DNS inválida" }
        return result
    }

    fun stats(): String = "Bloqueos: ${blocked.get()} · Errores DNS cifrado: ${errors.get()}\nDominios de suscripción: ${list.size}\n" +
        byApp.entries.sortedByDescending { it.value.get() }.joinToString("\n") { "${it.key}: ${it.value.get()}" } +
        "\nContadores del proceso; no equivalen a fugas ni bytes ahorrados."
}
