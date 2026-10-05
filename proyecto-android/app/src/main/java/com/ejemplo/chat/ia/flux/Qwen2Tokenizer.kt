package com.ejemplo.chat.ia.flux

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

/**
 * Small device-side Qwen2/GPT-2 compatible BPE tokenizer.
 * It reads the model's own vocab/merges/special-token files, so no network
 * tokenizer service is ever required.
 */
class Qwen2Tokenizer(root: File) {
    private val tokenToId: Map<String, Int>
    private val specialToId: Map<String, Int>
    private val merges: Map<Pair<String, String>, Int>
    private val bpeCache = HashMap<String, List<String>>()

    private val tokenPattern = Pattern.compile(
        "'(?i:[sdmt]|ll|ve|re)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s+[\\r\\n]+|\\s+(?!\\S)|\\s+"
    )

    private val byteEncoder: Array<Char> = buildByteEncoder()

    init {
        val tokenizerDir = File(root, "tokenizer")
        tokenToId = readVocab(File(tokenizerDir, "qwen_vocab.txt"))
        specialToId = readSpecial(File(tokenizerDir, "qwen_special.txt"))
        merges = readMerges(File(tokenizerDir, "qwen_merges.txt"))
    }

    /** Qwen3 chat template used by the FLUX.2 klein text encoder path. */
    fun renderUserPrompt(prompt: String): String =
        "<|im_start|>user\n${prompt}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n"

    /** Encode text and truncate to maxLength. No EOS/BOS token is added implicitly. */
    fun encode(text: String, maxLength: Int = 512): IntArray {
        val out = ArrayList<Int>(minOf(maxLength, text.length * 2))
        var cursor = 0
        while (cursor < text.length && out.size < maxLength) {
            var specialStart = -1
            var specialToken: String? = null
            for (candidate in specialToId.keys) {
                val p = text.indexOf(candidate, cursor)
                if (p >= 0 && (specialStart < 0 || p < specialStart || (p == specialStart && candidate.length > specialToken!!.length))) {
                    specialStart = p
                    specialToken = candidate
                }
            }

            if (specialStart == cursor && specialToken != null) {
                out += specialToId.getValue(specialToken)
                cursor += specialToken.length
                continue
            }

            val end = if (specialStart >= 0) specialStart else text.length
            val normal = text.substring(cursor, end)
            val matcher = tokenPattern.matcher(normal)
            while (matcher.find() && out.size < maxLength) {
                val piece = matcher.group()
                for (token in bpe(piece)) {
                    val id = tokenToId[token]
                        ?: throw IllegalStateException("Qwen vocab no contiene el token codificado: ${token.debugToken()}")
                    out += id
                    if (out.size >= maxLength) break
                }
            }
            cursor = end
        }
        return out.toIntArray()
    }

    fun padId(): Int {
        val id = specialToId.entries.firstOrNull { it.key == "<|endoftext|>" }?.value
            ?: specialToId.entries.firstOrNull { it.value == 151643 }?.value
            ?: throw IllegalStateException("qwen_special.txt no contiene el pad token requerido (151643)")
        check(id == 151643) { "Pad token incorrecto: $id; el contrato exige 151643" }
        return id
    }

    private fun bpe(piece: String): List<String> {
        bpeCache[piece]?.let { return it }
        if (piece.isEmpty()) return emptyList()

        val bytes = piece.toByteArray(Charsets.UTF_8)
        val chars = CharArray(bytes.size) { byteEncoder[bytes[it].toInt() and 0xff] }
        if (chars.size == 1) {
            val one = listOf(chars.concatToString())
            bpeCache[piece] = one
            return one
        }

        val symbols = chars.map { it.toString() }.toMutableList()
        while (symbols.size > 1) {
            var bestIndex = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until symbols.size - 1) {
                val rank = merges[symbols[i] to symbols[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = i
                }
            }
            if (bestIndex < 0) break
            symbols[bestIndex] += symbols.removeAt(bestIndex + 1)
        }

        val result = symbols.toList()
        bpeCache[piece] = result
        return result
    }

    /**
     * Acepta los formatos habituales de vocabulario Qwen/GPT-2:
     *  - JSON {"token": id}, o un tokenizer.json completo (model.vocab);
     *  - JSON array de tokens (el índice es el id);
     *  - líneas «token<tab|espacio>id» o «id<tab|espacio>token»;
     *  - un token por línea (el número de línea es el id).
     * Si ninguno encaja, el error incluye el tamaño y las primeras líneas para poder diagnosticarlo.
     */
    private fun readVocab(file: File): Map<String, Int> {
        require(file.isFile) { "Falta ${file.path}" }
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        parseJsonVocab(text)?.let { return it }
        parseLineVocab(text)?.let { return it }
        error("No se pudo leer qwen_vocab.txt (${file.length()} bytes). Primeras líneas: ${preview(text)}")
    }

    private fun parseJsonVocab(text: String): Map<String, Int>? {
        val head = text.trimStart().firstOrNull() ?: return null
        try {
            if (head == '{') {
                val json = JSONObject(text)
                val vocab = json.optJSONObject("model")?.optJSONObject("vocab")
                    ?: json.optJSONObject("vocab")
                    ?: json
                val map = HashMap<String, Int>(vocab.length())
                val keys = vocab.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = vocab.opt(k)
                    if (v is Number) map[k] = v.toInt() else return null
                }
                return map.takeIf { it.isNotEmpty() }
            }
            if (head == '[') {
                val arr = JSONArray(text)
                val map = HashMap<String, Int>(arr.length())
                for (i in 0 until arr.length()) {
                    val t = arr.opt(i)
                    if (t is String) map[t] = i else return null
                }
                return map.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            // No era JSON válido: se prueba el formato por líneas.
        }
        return null
    }

    private fun parseLineVocab(text: String): Map<String, Int>? {
        val lines = text.lines()
        val nonBlank = lines.count { it.isNotEmpty() }
        if (nonBlank == 0) return null
        // Ojo: «#» es un token válido, por eso aquí no se ignoran líneas que empiecen por #.
        val tokenFirst = HashMap<String, Int>(lines.size)
        val idFirst = HashMap<String, Int>(lines.size)
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            val last = if (line.lastIndexOf('\t') >= 0) line.lastIndexOf('\t') else line.lastIndexOf(' ')
            if (last > 0) line.substring(last + 1).trim().toIntOrNull()?.let { tokenFirst[line.substring(0, last)] = it }
            val first = line.indexOfFirst { it == '\t' || it == ' ' }
            if (first > 0 && first < line.length - 1) {
                line.substring(0, first).toIntOrNull()?.let { idFirst[line.substring(first + 1)] = it }
            }
        }
        if (tokenFirst.size > 1000 && tokenFirst.size >= nonBlank * 0.9) return tokenFirst
        if (idFirst.size > 1000 && idFirst.size >= nonBlank * 0.9) return idFirst
        if (nonBlank >= 100_000) {
            val map = HashMap<String, Int>(lines.size)
            lines.forEachIndexed { index, raw ->
                val token = raw.trimEnd('\r')
                if (token.isNotEmpty()) map[token] = index
            }
            return map
        }
        return null
    }

    private fun preview(text: String): String =
        text.lineSequence().take(3).joinToString(" | ") { it.take(50).debugToken() }
            .ifEmpty { "(vacío)" }

    private fun readSpecial(file: File): Map<String, Int> {
        require(file.isFile) { "Falta ${file.path}" }
        val text = file.readText().trim()
        if (text.startsWith("{")) {
            val json = JSONObject(text)
            val map = HashMap<String, Int>(json.length())
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.getInt(k)
            }
            return map
        }
        val map = HashMap<String, Int>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                val id = parts[1].trim().toIntOrNull()
                val idFirst = parts[0].toIntOrNull()
                if (id != null) map[parts[0]] = id
                else if (idFirst != null) map[parts[1].trim()] = idFirst
            }
        }
        return map
    }

    private fun readMerges(file: File): Map<Pair<String, String>, Int> {
        require(file.isFile) { "Falta ${file.path}" }
        val map = HashMap<Pair<String, String>, Int>()
        var rank = 0
        file.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#version")) return@forEach
            val p = line.split(Regex("\\s+"))
            if (p.size == 2) {
                map[p[0] to p[1]] = rank++
            }
        }
        return map
    }

    private fun buildByteEncoder(): Array<Char> {
        val bs = ArrayList<Int>(256)
        for (i in 0x21..0x7e) bs += i
        for (i in 0xa1..0xac) bs += i
        for (i in 0xae..0xff) bs += i
        val cs = ArrayList<Int>(bs)
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs += b
                cs += 256 + n++
            }
        }
        return Array(256) { Char(cs[bs.indexOf(it)]) }
    }

    private fun String.debugToken(): String =
        replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
