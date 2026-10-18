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

        // Candidatos genéricos de alta entropía (posibles claves no reconocidas
        // por ningún patrón concreto): cadenas largas, sin espacios, entropía alta.
        strings.filter { it.length in 20..100 && !it.contains(' ') }
            .forEach { s ->
                val entropia = shannonEntropy(s)
                if (entropia > 4.0) {
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

        return encontrados.distinctBy { Triple(it.type, it.value, it.sourceFile) }
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

    private companion object {
        const val BLOQUE = 64 * 1024
        const val MIN_RACHA = 5
        const val MAX_RACHA = 8192
        const val MAX_STRINGS = 400_000
    }
}
