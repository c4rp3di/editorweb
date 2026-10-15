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

    private val printableRun = Regex("[\\x20-\\x7E]{5,}")
    private val urlPattern = Regex("https?://[\\w.\\-]+(?::\\d+)?(?:/[\\w\\-./%?&=+#]*)?")
    private val patterns = mapOf(
        "aws_key" to Regex("AKIA[0-9A-Z]{16}"),
        "jwt" to Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
        "generic_api_key_assignment" to Regex("(?i)(api[_-]?key|secret|token|password)\\s*[:=]\\s*[\"']([A-Za-z0-9_\\-]{12,})[\"']")
    )

    fun extractStrings(dexFile: File): List<String> {
        val texto = dexFile.readBytes().toString(Charsets.ISO_8859_1)
        return printableRun.findAll(texto).map { it.value }.distinct().toList()
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

    fun findSecrets(strings: List<String>, analysisId: String, sourceFile: String): List<SecretEntity> {
        val encontrados = mutableListOf<SecretEntity>()

        for (s in strings) {
            patterns.forEach { (tipo, regex) ->
                regex.findAll(s).forEach { m ->
                    val valor = m.value
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

        return encontrados.distinctBy { it.value }
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
}
