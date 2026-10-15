package com.vigia.app.analyze.sig

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import com.vigia.app.analyze.AjustesAnalisis
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.IocEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class IocParseado(val tipo: String, val valor: String)

class ResultadoIoc(val fuentes: Int, val indicadores: Int, val errores: List<String>)

/**
 * Lector de STIX2 (formato de MVT y Amnistía). Solo se extraen los tipos que la herramienta puede
 * comprobar con lo que recoge: dominios, IPs, nombres de paquete, procesos y URLs.
 */
object StixParser {
    private val PATRON = Regex("([A-Za-z0-9-]+):([A-Za-z0-9_.\\-]+)\\s*=\\s*'((?:[^'\\\\]|\\\\.)*)'")

    fun parsear(entrada: InputStream): List<IocParseado> {
        val lista = ArrayList<IocParseado>()
        val vistos = HashSet<String>()
        val lector = JsonReader(InputStreamReader(entrada, Charsets.UTF_8))
        lector.isLenient = true
        try {
            lector.beginObject()
            while (lector.hasNext()) {
                val nombre = lector.nextName()
                if (nombre == "objects") {
                    leerObjetos(lector, lista, vistos)
                } else {
                    lector.skipValue()
                }
            }
            lector.endObject()
        } finally {
            lector.close()
        }
        return lista
    }

    private fun leerObjetos(l: JsonReader, lista: MutableList<IocParseado>, vistos: MutableSet<String>) {
        l.beginArray()
        while (l.hasNext()) {
            l.beginObject()
            var tipo = ""
            var patron = ""
            while (l.hasNext()) {
                when (l.nextName()) {
                    "type" -> tipo = texto(l)
                    "pattern" -> patron = texto(l)
                    else -> l.skipValue()
                }
            }
            l.endObject()
            if (tipo == "indicator" && patron.isNotEmpty()) extraer(patron, lista, vistos)
        }
        l.endArray()
    }

    private fun texto(l: JsonReader): String {
        return if (l.peek() == JsonToken.STRING) {
            l.nextString()
        } else {
            l.skipValue()
            ""
        }
    }

    private fun extraer(patron: String, lista: MutableList<IocParseado>, vistos: MutableSet<String>) {
        for (m in PATRON.findAll(patron)) {
            val clave = m.groupValues[1] + ":" + m.groupValues[2]
            val valor = m.groupValues[3].replace("\\'", "'").trim()
            if (valor.isEmpty()) continue
            val tipo = when (clave) {
                "domain-name:value" -> "dominio"
                "ipv4-addr:value", "ipv6-addr:value" -> "ip"
                "app:id" -> "paquete"
                "process:name" -> "proceso"
                "url:value" -> "url"
                else -> null
            }
            if (tipo == null) continue
            val normal = if (tipo == "dominio" || tipo == "ip") valor.lowercase() else valor
            if (vistos.add(tipo + "|" + normal)) lista.add(IocParseado(tipo, normal))
        }
    }
}

/** Descarga, importa y borra indicadores. Solo se conecta a internet cuando el usuario lo pide. */
object IocManager {
    private const val URL_INDICE = "https://raw.githubusercontent.com/mvt-project/mvt-indicators/main/indicators.yaml"
    private const val MAX_BYTES = 40L * 1024L * 1024L

    suspend fun actualizar(ctx: Context): ResultadoIoc = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val ajustes = AjustesAnalisis(app)
        val errores = ArrayList<String>()
        val fuentes = ArrayList<Pair<String, String>>()

        if (ajustes.usarIndiceMvt) {
            try {
                val texto = descargarTexto(URL_INDICE)
                fuentes.addAll(parsearIndice(texto))
                if (fuentes.isEmpty()) errores.add("El índice de MVT no contenía archivos reconocibles")
            } catch (e: Exception) {
                errores.add("Índice de MVT: " + e.javaClass.simpleName + " " + (e.message ?: ""))
            }
        }
        for (url in ajustes.fuentesExtra()) {
            fuentes.add(Pair(url, url))
        }

        val dao = AppDatabase.obtener(app).analisisDao()
        var total = 0
        var correctas = 0
        for ((nombre, url) in fuentes) {
            try {
                val lista = descargarYParsear(url)
                guardar(app, url, lista)
                total += lista.size
                correctas++
            } catch (e: Exception) {
                errores.add(nombre + ": " + e.javaClass.simpleName + " " + (e.message ?: ""))
            }
        }
        if (correctas > 0) ajustes.ultimaActualizacionIoc = System.currentTimeMillis()
        dao.contarIocs()
        ResultadoIoc(correctas, total, errores)
    }

    suspend fun importarArchivo(ctx: Context, uri: Uri): ResultadoIoc = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val errores = ArrayList<String>()
        var total = 0
        var correctas = 0
        val fuente = "archivo:" + (uri.lastPathSegment ?: "importado")
        try {
            val entrada = app.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
            val lista = entrada.use { StixParser.parsear(LimitedInputStream(it, MAX_BYTES)) }
            guardar(app, fuente, lista)
            total = lista.size
            correctas = 1
        } catch (e: Exception) {
            errores.add(e.javaClass.simpleName + " " + (e.message ?: ""))
        }
        ResultadoIoc(correctas, total, errores)
    }

    suspend fun borrarTodo(ctx: Context) {
        withContext(Dispatchers.IO) {
            AppDatabase.obtener(ctx.applicationContext).analisisDao().borrarTodosIocs()
        }
    }

    private suspend fun guardar(app: Context, fuente: String, lista: List<IocParseado>) {
        val dao = AppDatabase.obtener(app).analisisDao()
        val ahora = System.currentTimeMillis()
        dao.borrarIocsDeFuente(fuente)
        val filas = lista.map { IocEntity(tipo = it.tipo, valor = it.valor, fuente = fuente, fechaAlta = ahora) }
        var i = 0
        while (i < filas.size) {
            dao.insertarIocs(filas.subList(i, minOf(i + 500, filas.size)))
            i += 500
        }
    }

    private fun abrir(url: String): HttpURLConnection {
        if (!url.startsWith("https://")) throw IOException("Solo se admiten direcciones https")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Vigia")
        if (c.responseCode != 200) {
            val codigo = c.responseCode
            c.disconnect()
            throw IOException("HTTP " + codigo)
        }
        return c
    }

    private fun descargarTexto(url: String): String {
        val c = abrir(url)
        try {
            return LimitedInputStream(c.inputStream, 2L * 1024L * 1024L).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun descargarYParsear(url: String): List<IocParseado> {
        val c = abrir(url)
        try {
            return StixParser.parsear(LimitedInputStream(c.inputStream, MAX_BYTES))
        } finally {
            c.disconnect()
        }
    }

    /**
     * Lee el índice de MVT (indicators.yaml) con un lector sencillo: busca las entradas de tipo github y
     * construye la dirección raw de cada archivo STIX2.
     */
    fun parsearIndice(texto: String): List<Pair<String, String>> {
        val res = ArrayList<Pair<String, String>>()
        var tipo = ""
        var nombre = ""
        var owner = ""
        var repo = ""
        var rama = "main"
        var ruta = ""

        fun cerrar() {
            val valida = tipo == "github" && owner.isNotEmpty() && repo.isNotEmpty() &&
                (ruta.endsWith(".stix2") || ruta.endsWith(".json"))
            if (valida) {
                val url = "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + rama + "/" + ruta
                res.add(Pair(if (nombre.isEmpty()) ruta else nombre, url))
            }
            tipo = ""
            nombre = ""
            owner = ""
            repo = ""
            rama = "main"
            ruta = ""
        }

        val clave = Regex("^([A-Za-z_]+):\\s*(.*)$")
        for (linea in texto.lineSequence()) {
            var contenido = linea.trim()
            if (contenido.isEmpty() || contenido.startsWith("#")) continue
            if (contenido.startsWith("-")) {
                cerrar()
                contenido = contenido.removePrefix("-").trim()
            }
            val m = clave.find(contenido) ?: continue
            val valor = m.groupValues[2].trim().trim('\'', '"')
            when (m.groupValues[1]) {
                "type" -> tipo = valor
                "name" -> nombre = valor
                "owner" -> owner = valor
                "repo" -> repo = valor
                "branch" -> rama = if (valor.isEmpty()) "main" else valor
                "path" -> ruta = valor
            }
        }
        cerrar()
        return res
    }

    /** Evita descargas descomunales: corta si se supera el límite. */
    private class LimitedInputStream(entrada: InputStream, private val limite: Long) : FilterInputStream(entrada) {
        private var leidos = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) contar(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) contar(n.toLong())
            return n
        }

        private fun contar(n: Long) {
            leidos += n
            if (leidos > limite) throw IOException("El archivo supera el límite de tamaño")
        }
    }
}
