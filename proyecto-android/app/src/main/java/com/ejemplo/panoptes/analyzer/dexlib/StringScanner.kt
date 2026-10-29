package com.carpe.panoptes.analyzer.dexlib

import com.carpe.panoptes.core.db.EndpointEntity
import com.carpe.panoptes.core.db.SecretEntity
import java.io.File
import kotlin.math.ln

/**
 * "Extracción de strings: dexlib2 + regex" (§6.2 del plan).
 * No depende de APIs internas frágiles del string-pool de dexlib2: extrae
 * cadenas imprimibles del .dex al estilo `strings` de Unix y les aplica
 * patrones conocidos. Es una heurística — cada candidato debe revisarse,
 * no es una confirmación.
 */
class StringScanner {

    private val urlPattern = Regex("https?://[\\w.\\-]+(?::\\d+)?(?:/[\\w\\-./%?&=+#]*)?")
    private val patterns = mapOf(
        "aws_key" to Regex("AKIA[0-9A-Z]{16}"),
        "jwt" to Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
        "generic_api_key_assignment" to Regex("(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*[\"']([A-Za-z0-9_\\-]{12,})[\"']")
    )

    // Reintroducido: esto se había perdido en algún punto entre v1.0.7 y v1.0.18
    // (probablemente al reescribir extractStrings para H-09 sobre una base anterior
    // al arreglo de ruido). Sin esto, "generic_high_entropy" marca prácticamente
    // cualquier nombre de clase/paquete de cualquier librería de terceros como
    // "secreto" — en una app con Chaquopy+Compose+Room+etc. eso son decenas de
    // miles de falsos positivos, no una exageración: pasó de verdad (94.277).
    private val HOSTS_RUIDO_XML = setOf(
        "schemas.android.com", "www.w3.org", "xmlpull.org", "schemas.xmlsoap.org",
        "apache.org", "purl.org", "java.sun.com", "schemas.openxmlformats.org"
    )
    private val descriptorDex = Regex("^L[\\w/$]+;\$")
    private val nombreCualificado = Regex("^[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*){2,}\$")

    private fun pareceIdentificadorDeCodigo(s: String): Boolean {
        if (descriptorDex.matches(s)) return true
        if (nombreCualificado.matches(s)) return true
        if (s.count { it == '/' } >= 2) return true
        if (s.startsWith("Landroid") || s.startsWith("Lkotlin") || s.startsWith("Landroidx")) return true
        return false
    }

    /**
     * Cadenas ASCII imprimibles (>= 5 caracteres), leyendo el DEX por bloques
     * (auditoría H-09: antes se cargaba el archivo entero en memoria como String).
     * Una racha muy larga se parte en trozos de [MAX_RACHA] caracteres, y el
     * conjunto devuelto se acota a [MAX_STRINGS] cadenas distintas.
     */
    fun extractStrings(dexFile: File): List<String> {
        val resultado = LinkedHashSet<String>()
        val actual = StringBuilder()
        dexFile.inputStream().buffered(BLOQUE).use { input ->
            val buf = ByteArray(BLOQUE)
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                for (i in 0 until n) {
                    val b = buf[i].toInt() and 0xFF
                    if (b in 0x20..0x7E) {
                        actual.append(b.toChar())
                        if (actual.length >= MAX_RACHA) volcar(actual, resultado)
                    } else {
                        volcar(actual, resultado)
                    }
                }
                if (resultado.size >= MAX_STRINGS) break
            }
        }
        volcar(actual, resultado)
        return resultado.toList()
    }

    private fun volcar(actual: StringBuilder, destino: MutableSet<String>) {
        if (actual.length >= MIN_RACHA && destino.size < MAX_STRINGS) destino.add(actual.toString())
        actual.setLength(0)
    }

    fun findEndpoints(strings: List<String>, analysisId: String, source: String): List<EndpointEntity> =
        strings.flatMap { s -> urlPattern.findAll(s).map { it.value } }
            .distinct()
            .filterNot { url -> HOSTS_RUIDO_XML.any { host -> url.contains(host) } }
            .map { url ->
                EndpointEntity(
                    analysisId = analysisId,
                    url = url,
                    source = source,
                    confidence = if (url.startsWith("https://")) 0.9f else 0.6f
                )
            }

    /**
     * Candidatos a secreto. [sourceFile] identifica el DEX concreto de donde salen las
     * cadenas (p.ej. "base/classes2.dex"); la deduplicación es por tipo + valor + origen,
     * así que el mismo valor en DEX distintos se conserva como hallazgos separados.
     * Para "generic_api_key_assignment" el valor guardado es el SECRETO capturado (grupo 2),
     * no toda la asignación.
     */
    fun findSecrets(strings: List<String>, analysisId: String, sourceFile: String): List<SecretEntity> {
        val encontrados = mutableListOf<SecretEntity>()

        for (s in strings) {
            patterns.forEach { (tipo, regex) ->
                regex.findAll(s).forEach { m ->
                    val valor = if (m.groupValues.size > 2 && m.groupValues[2].isNotEmpty()) m.groupValues[2] else m.value
                    encontrados.add(
                        SecretEntity(
                            analysisId = analysisId,
                            type = tipo,
                            value = valor,
                            sourceFile = sourceFile,
                            entropy = shannonEntropy(valor)
                        )
                    )
                }
            }
        }

        // Candidatos genéricos de alta entropía (posibles claves no reconocidas por
        // ningún patrón concreto). Se descartan antes las cadenas con forma de
        // identificador de código (descriptor dex, nombre cualificado, ruta) y se
        // exige mezcla fuerte mayús+minús+dígito — un nombre de clase casi nunca la
        // tiene, una clave real casi siempre sí. Umbral de entropía 4.3, no 4.0.
        strings.asSequence()
            .filter { it.length in 20..100 && !it.contains(' ') }
            .filterNot { pareceIdentificadorDeCodigo(it) }
            .filter { s -> s.any { it.isDigit() } && s.any { it.isUpperCase() } && s.any { it.isLowerCase() } }
            .forEach { s ->
                val entropia = shannonEntropy(s)
                if (entropia > 4.3) {
                    encontrados.add(
                        SecretEntity(
                            analysisId = analysisId,
                            type = "generic_high_entropy",
                            value = s,
                            sourceFile = sourceFile,
                            entropy = entropia
                        )
                    )
                }
            }

        val unicos = encontrados.distinctBy { Triple(it.type, it.value, it.sourceFile) }
        return if (unicos.size > MAX_SECRETOS_POR_ANALISIS) {
            unicos.sortedByDescending { it.entropy }.take(MAX_SECRETOS_POR_ANALISIS)
        } else {
            unicos
        }
    }

    private fun shannonEntropy(s: String): Float {
        if (s.isEmpty()) return 0f
        val freq = s.groupingBy { it }.eachCount()
        val len = s.length.toDouble()
        val entropia = freq.values.sumOf { count ->
            val p = count / len
            -p * ln(p) / ln(2.0)
        }
        return entropia.toFloat()
    }

    companion object {
        private const val BLOQUE = 64 * 1024
        private const val MIN_RACHA = 5
        private const val MAX_RACHA = 8192
        private const val MAX_STRINGS = 400_000
        /** Límite de seguridad aparte del filtrado de arriba — visible para que el
         *  orquestador pueda avisar en el Finding si el recuento se truncó. */
        const val MAX_SECRETOS_POR_ANALISIS = 500
    }
}
