package com.carpe.panoptes.core.files

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Acceso de solo lectura a los ficheros que genera un análisis (código descompilado, recursos
 * decodificados, DEX extraídos...) bajo filesDir/analysis/<id>/. Todas las rutas son relativas a
 * la raíz del análisis y se validan contra ella (sin ".." ni enlaces simbólicos que escapen).
 */
object AnalysisFiles {

    data class Entrada(val nombre: String, val ruta: String, val esDir: Boolean, val tamano: Long)

    sealed class Contenido {
        data class Texto(val texto: String, val truncado: Boolean, val tamano: Long) : Contenido()
        data class Binario(val tamano: Long) : Contenido()
    }

    data class Coincidencia(val ruta: String, val linea: Int, val texto: String)

    data class ResultadoBusqueda(
        val coincidencias: List<Coincidencia>,
        val archivosRevisados: Int,
        val truncado: Boolean
    )

    const val MAX_LECTURA_BYTES = 512 * 1024
    private const val MAX_BUSQUEDA_ARCHIVO = 2L * 1024 * 1024
    private const val MAX_BUSQUEDA_ARCHIVOS = 20_000
    private const val MAX_BUSQUEDA_RESULTADOS = 300
    private const val MAX_LINEA_COINCIDENCIA = 240
    private const val MAX_ZIP_BYTES = 500L * 1024 * 1024

    /** Resuelve [relativa] dentro de [raiz]; null si no existe o intenta salir de ella. */
    fun resolver(raiz: File, relativa: String): File? {
        val base = try { raiz.canonicalFile } catch (e: Exception) { return null }
        val limpia = relativa.trim('/')
        val destino = try { (if (limpia.isEmpty()) base else File(base, limpia)).canonicalFile } catch (e: Exception) { return null }
        val dentro = destino == base || destino.path.startsWith(base.path + File.separator)
        return if (dentro && destino.exists()) destino else null
    }

    private fun rutaRelativa(raiz: File, f: File): String =
        f.relativeTo(raiz.canonicalFile).path.replace(File.separatorChar, '/')

    fun listar(raiz: File, relativa: String): List<Entrada> {
        val dir = resolver(raiz, relativa)?.takeIf { it.isDirectory } ?: return emptyList()
        val base = raiz.canonicalFile
        return (dir.listFiles() ?: emptyArray())
            .mapNotNull { hijo ->
                val real = try { hijo.canonicalFile } catch (e: Exception) { return@mapNotNull null }
                if (!(real.path.startsWith(base.path + File.separator))) return@mapNotNull null // enlace que escapa
                Entrada(hijo.name, rutaRelativa(raiz, real), real.isDirectory, if (real.isFile) real.length() else 0L)
            }
            .sortedWith(compareByDescending<Entrada> { it.esDir }.thenBy { it.nombre.lowercase() })
    }

    private fun esBinario(muestra: ByteArray, n: Int): Boolean {
        for (i in 0 until n) if (muestra[i].toInt() == 0) return true
        return false
    }

    fun leer(raiz: File, relativa: String, maxBytes: Int = MAX_LECTURA_BYTES): Contenido? {
        val f = resolver(raiz, relativa)?.takeIf { it.isFile } ?: return null
        val tamano = f.length()
        f.inputStream().use { input ->
            val buf = ByteArray(minOf(maxBytes.toLong(), tamano).toInt().coerceAtLeast(0))
            var leidos = 0
            while (leidos < buf.size) {
                val n = input.read(buf, leidos, buf.size - leidos)
                if (n <= 0) break
                leidos += n
            }
            if (esBinario(buf, minOf(leidos, 4096))) return Contenido.Binario(tamano)
            return Contenido.Texto(String(buf, 0, leidos, Charsets.UTF_8), tamano > leidos, tamano)
        }
    }

    /** Búsqueda de texto en todos los ficheros de texto bajo [relativa]. [cancelado] se consulta por fichero. */
    fun buscar(
        raiz: File,
        relativa: String,
        consulta: String,
        distinguirMayusculas: Boolean = false,
        cancelado: () -> Boolean = { false }
    ): ResultadoBusqueda {
        val inicio = resolver(raiz, relativa) ?: return ResultadoBusqueda(emptyList(), 0, false)
        if (consulta.isBlank()) return ResultadoBusqueda(emptyList(), 0, false)
        val aguja = if (distinguirMayusculas) consulta else consulta.lowercase()
        val resultados = ArrayList<Coincidencia>()
        var revisados = 0
        var truncado = false
        val base = raiz.canonicalFile
        val ficheros = inicio.walkTopDown()
            .onEnter { d -> try { d.canonicalPath.startsWith(base.path) } catch (e: Exception) { false } }
            .filter { it.isFile }
        for (f in ficheros) {
            if (cancelado()) { truncado = true; break }
            if (revisados >= MAX_BUSQUEDA_ARCHIVOS || resultados.size >= MAX_BUSQUEDA_RESULTADOS) { truncado = true; break }
            if (f.length() > MAX_BUSQUEDA_ARCHIVO) continue
            val real = try { f.canonicalFile } catch (e: Exception) { continue }
            if (!real.path.startsWith(base.path + File.separator)) continue
            val muestra = ByteArray(4096)
            val n = real.inputStream().use { it.read(muestra) }
            if (n > 0 && esBinario(muestra, n)) continue
            revisados++
            var numero = 0
            real.bufferedReader(Charsets.UTF_8).useLines { lineas ->
                for (linea in lineas) {
                    numero++
                    val comparar = if (distinguirMayusculas) linea else linea.lowercase()
                    if (comparar.contains(aguja)) {
                        resultados.add(Coincidencia(rutaRelativa(raiz, real), numero, linea.trim().take(MAX_LINEA_COINCIDENCIA)))
                        if (resultados.size >= MAX_BUSQUEDA_RESULTADOS) { truncado = true; break }
                    }
                }
            }
        }
        return ResultadoBusqueda(resultados, revisados, truncado)
    }

    /** Comprime [relativa] (fichero o carpeta) en [destino]. Devuelve cuántos ficheros entraron. */
    fun comprimir(raiz: File, relativa: String, destino: File): Int {
        val origen = resolver(raiz, relativa) ?: throw IllegalArgumentException("Ruta no válida")
        val base = raiz.canonicalFile
        destino.parentFile?.mkdirs()
        var total = 0L
        var n = 0
        ZipOutputStream(FileOutputStream(destino).buffered()).use { zip ->
            val raizZip = if (origen == base) null else origen.parentFile
            val ficheros = if (origen.isFile) sequenceOf(origen) else origen.walkTopDown()
                .onEnter { d -> try { d.canonicalPath.startsWith(base.path) } catch (e: Exception) { false } }
                .filter { it.isFile }
            for (f in ficheros) {
                val real = try { f.canonicalFile } catch (e: Exception) { continue }
                if (!real.path.startsWith(base.path + File.separator)) continue
                total += real.length()
                if (total > MAX_ZIP_BYTES) throw IllegalStateException("Demasiado grande para exportar (más de 500 MB)")
                val nombre = (raizZip?.let { real.relativeTo(it) } ?: real.relativeTo(base)).path.replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(nombre))
                real.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                n++
            }
        }
        return n
    }
}
