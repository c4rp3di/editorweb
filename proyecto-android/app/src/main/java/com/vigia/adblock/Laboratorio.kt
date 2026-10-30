package com.vigia.adblock

import android.content.Context
import com.vigia.mitm.EventosMitm
import com.vigia.mitm.FiltroContenido
import com.vigia.mitm.RespuestaFalsa
import com.vigia.mitm.TipoRecurso
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Las técnicas de AntiAds. Cada una se activa por separado en el Laboratorio. */
object Laboratorio {
    class Tecnica(val id: String, val nombre: String, val descripcion: String, val porDefecto: Boolean)

    val TECNICAS = listOf(
        Tecnica("clasico", "Bloqueo clásico (0 bytes)", "Lo que hace el Soberano+: los recursos de la lista se sustituyen por un 200 vacío. Apágalo para ver qué pasa sin él.", true),
        Tecnica("vast", "VAST vacío válido", "Las peticiones de anuncios de vídeo reciben un «no hay anuncio» bien formado en lugar de nada. El reproductor arranca el vídeo.", true),
        Tecnica("stubs", "Scripts sustitutos", "Google Publisher Tag, IMA SDK y adsbygoogle se sustituyen por versiones que no hacen nada pero no rompen la página.", true),
        Tecnica("yt", "YouTube: borrar datos de anuncios", "Script que quita adPlacements/playerAds de las respuestas del reproductor antes de que la web los lea.", true),
        Tecnica("skip", "Saltador por JavaScript", "Si un anuncio llega a sonar: lo silencia, lo adelanta al final y pulsa «Saltar». En YouTube y, de forma genérica, en otras webs.", true),
        Tecnica("css", "Ocultar huecos (CSS)", "Oculta los contenedores de anuncios que quedan vacíos.", true),
        Tecnica("hls", "Limpiar listas HLS (experimental)", "Quita de las listas .m3u8 los tramos marcados como anuncio (CUE-OUT/CUE-IN). Solo sirve si el sitio los marca.", false)
    )

    private val cache = ConcurrentHashMap<String, Boolean>()
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("antiads_lab", Context.MODE_PRIVATE)

    fun activa(c: Context, id: String): Boolean =
        cache.getOrPut(id) { prefs(c).getBoolean("t_" + id, TECNICAS.firstOrNull { it.id == id }?.porDefecto ?: false) }

    fun poner(c: Context, id: String, v: Boolean) {
        cache[id] = v
        prefs(c).edit().putBoolean("t_" + id, v).apply()
    }

    private val conteo = ConcurrentHashMap<String, AtomicInteger>()
    fun contar(t: String) { conteo.getOrPut(t) { AtomicInteger() }.incrementAndGet() }
    fun veces(t: String): Int = conteo[t]?.get() ?: 0
    fun reiniciarConteo() = conteo.clear()
}

/** Registro de lo que han hecho las técnicas. Solo en memoria. Se puede copiar y enviar para diagnosticar. */
object Diagnostico {
    private const val MAX = 600
    private val lineas = ArrayDeque<String>()
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun anotar(tipo: String, texto: String) {
        if (lineas.size >= MAX) lineas.removeFirst()
        lineas.addLast(hora.format(Date()) + "  " + tipo.padEnd(10) + " " + texto.take(110))
    }

    @Synchronized
    fun texto(ultimas: Int = MAX): String = lineas.toList().takeLast(ultimas).joinToString("\n")

    @Synchronized
    fun vaciar() = lineas.clear()
}

/** Reglas editables (JSON): patrones de peticiones y técnicas por sitio. */
class Reglas(val vast: List<String>, val stubs: List<Pair<String, String>>, val sitios: List<Pair<String, Set<String>>>) {

    /** Técnicas de página para [host]: las de «*» más las del sitio que mejor encaje (sufijo de dominio). */
    fun tecnicasPara(host: String): Set<String> {
        val res = HashSet<String>()
        for ((h, t) in sitios) if (h == "*") res.addAll(t)
        val mejor = sitios.filter { it.first != "*" && (host == it.first || host.endsWith("." + it.first)) }.maxByOrNull { it.first.length }
        if (mejor != null) res.addAll(mejor.second)
        return res
    }

    companion object {
        fun parse(texto: String): Reglas {
            val j = JSONObject(texto)
            val vast = ArrayList<String>()
            j.optJSONArray("vast")?.let { for (i in 0 until it.length()) vast.add(it.getString(i).lowercase()) }
            val stubs = ArrayList<Pair<String, String>>()
            j.optJSONArray("stubs")?.let {
                for (i in 0 until it.length()) {
                    val o = it.getJSONObject(i)
                    stubs.add(Pair(o.getString("patron").lowercase(), o.getString("stub")))
                }
            }
            val sitios = ArrayList<Pair<String, Set<String>>>()
            j.optJSONArray("sitios")?.let {
                for (i in 0 until it.length()) {
                    val o = it.getJSONObject(i)
                    val t = HashSet<String>()
                    val a = o.optJSONArray("tecnicas")
                    if (a != null) for (k in 0 until a.length()) t.add(a.getString(k))
                    sitios.add(Pair(o.getString("host").lowercase(), t))
                }
            }
            return Reglas(vast, stubs, sitios)
        }

        fun archivo(c: Context) = File(c.filesDir, "reglas.json")

        /** Las importadas si existen y son válidas; si no, las integradas. */
        fun cargar(c: Context): Reglas {
            val f = archivo(c)
            if (f.exists()) {
                try { return parse(f.readText()) } catch (e: Exception) { Diagnostico.anotar("reglas", "importadas no válidas, uso las integradas: " + e.message) }
            }
            return parse(c.assets.open("reglas.json").bufferedReader().use { it.readText() })
        }
    }
}

object ReglasEstado {
    @Volatile
    var actual: Reglas? = null

    fun asegurar(c: Context): Reglas = actual ?: Reglas.cargar(c.applicationContext).also { actual = it }
    fun recargar(c: Context) { actual = Reglas.cargar(c.applicationContext) }
}

/**
 * El filtro de contenido de AntiAds: aplica las técnicas activas. Las de red (VAST, scripts sustitutos, bloqueo
 * clásico) actúan sobre cualquier petición según su URL; las de página (scriptlets, saltador, CSS, HLS) según el
 * sitio que se está viendo, que sale de las reglas.
 */
class MotorReglas(contexto: Context, base: FiltroAdBlock) : FiltroContenido {
    private val c = contexto.applicationContext
    private val clasico = FiltroContenidoAdBlock(base)

    private fun reglas(): Reglas = ReglasEstado.asegurar(c)

    override fun responder(host: String, ruta: String, tipo: TipoRecurso, metodo: String, origen: String?): RespuestaFalsa? {
        val url = (host + ruta).lowercase()
        val r = reglas()
        if (Laboratorio.activa(c, "stubs")) {
            for ((patron, stub) in r.stubs) {
                if (url.contains(patron)) {
                    val js = when (stub) {
                        "gpt" -> LaboratorioJs.STUB_GPT
                        "ima" -> LaboratorioJs.STUB_IMA
                        "adsbygoogle" -> LaboratorioJs.STUB_ADSBYGOOGLE
                        else -> null
                    } ?: continue
                    return RespuestaFalsa("stub:" + stub, "application/javascript; charset=utf-8", js.toByteArray(Charsets.UTF_8))
                }
            }
        }
        if (Laboratorio.activa(c, "vast") && r.vast.any { url.contains(it) }) {
            val vmap = url.contains("vmap")
            return RespuestaFalsa(
                if (vmap) "vmap" else "vast", "application/xml; charset=utf-8",
                (if (vmap) LaboratorioJs.VMAP_VACIO else LaboratorioJs.VAST_VACIO).toByteArray(Charsets.UTF_8)
            )
        }
        return null
    }

    override fun bloquear(host: String, ruta: String, tipo: TipoRecurso): Boolean =
        Laboratorio.activa(c, "clasico") && clasico.bloquear(host, ruta, tipo)

    override fun cssCosmetico(host: String): String? {
        if (!Laboratorio.activa(c, "css")) return null
        val t = reglas().tecnicasPara(host)
        if (!t.contains("css")) return null
        return FiltroContenidoAdBlock.CSS + (if (t.contains("yt")) "\n" + LaboratorioJs.CSS_YOUTUBE else "")
    }

    override fun scriptInicial(host: String): String? {
        val t = reglas().tecnicasPara(host)
        val sb = StringBuilder()
        if (t.contains("yt") && Laboratorio.activa(c, "yt")) sb.append(LaboratorioJs.YT_PRUNE).append('\n')
        if (t.contains("skip") && Laboratorio.activa(c, "skip")) {
            val yt = host == "youtube.com" || host.endsWith(".youtube.com") || host.endsWith("youtube-nocookie.com")
            sb.append(if (yt) LaboratorioJs.SKIP_YT else LaboratorioJs.SKIP_GENERICO).append('\n')
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    override fun tratarHls(host: String, ruta: String): Boolean =
        Laboratorio.activa(c, "hls") && reglas().tecnicasPara(host).contains("hls")

    /** Quita de una lista HLS los tramos entre #EXT-X-CUE-OUT y #EXT-X-CUE-IN y las marcas de intersticiales. */
    override fun reescribirTexto(host: String, ruta: String, texto: String): String? {
        if (!texto.startsWith("#EXTM3U")) return null
        val salida = ArrayList<String>()
        var enAnuncio = false
        var cambio = false
        for (l in texto.split('\n')) {
            val t = l.trim()
            when {
                t.startsWith("#EXT-X-CUE-OUT") -> { enAnuncio = true; cambio = true }
                t.startsWith("#EXT-X-CUE-IN") -> { enAnuncio = false; cambio = true }
                t.startsWith("#EXT-X-DATERANGE") && (t.contains("SCTE35-OUT") || t.contains("com.apple.hls.interstitial")) -> cambio = true
                enAnuncio && !t.startsWith("#EXT-X-ENDLIST") -> cambio = true
                else -> salida.add(l)
            }
        }
        return if (cambio) salida.joinToString("\n") else null
    }
}

/** Contadores y diagnóstico de lo que ocurre en el proxy. */
object EventosLab : EventosMitm {
    override fun alBloquear(host: String, ruta: String, tipo: TipoRecurso) {
        MitmEstado.vaciados.incrementAndGet()
        Laboratorio.contar("clasico")
        Estadisticas.registrar(host)
        Diagnostico.anotar("clasico", host + ruta)
    }

    override fun alInyectarCss(host: String) {
        MitmEstado.paginasLimpias.incrementAndGet()
        Laboratorio.contar("css")
        Diagnostico.anotar("css", host)
    }

    override fun alConexionDescifrada(host: String) { MitmEstado.descifradas.incrementAndGet() }

    override fun alFallbackPinning(app: String, host: String, motivo: String) {
        MitmEstado.rechazos.incrementAndGet()
        Diagnostico.anotar("rechazo", app + " → " + host + " (" + motivo.take(50) + ")")
    }

    override fun alTecnica(tecnica: String, host: String, ruta: String) {
        Laboratorio.contar(tecnica.substringBefore(':'))
        Diagnostico.anotar(tecnica, host + ruta)
    }

    override fun alError(host: String, mensaje: String) { Diagnostico.anotar("error", host + " " + mensaje) }
}
