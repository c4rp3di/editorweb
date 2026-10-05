package com.ejemplo.chat.ia.flux

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
        "<|im_start|>user\n${prompt}<|im_end|>\n<|im_start|>assistant\n"

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

    fun padId(): Int =
        specialToId["<|endoftext|>"]
            ?: specialToId["<|im_end|>"]
            ?: throw IllegalStateException("qwen_special.txt no contiene un token de padding seguro")

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

    private fun readVocab(file: File): Map<String, Int> {
        require(file.isFile) { "Falta ${file.path}" }
        val text = file.readText()
        try {
            val json = JSONObject(text)
            val map = HashMap<String, Int>(json.length())
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.getInt(k)
            }
            if (map.isNotEmpty()) return map
        } catch (_: Exception) {
            // Some exporters use a line-oriented txt representation.
        }
        val map = HashMap<String, Int>()
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val tab = line.lastIndexOf('\t')
            val split = if (tab >= 0) tab else line.lastIndexOf(' ')
            if (split <= 0) return@forEach
            val token = line.substring(0, split)
            val id = line.substring(split + 1).trim().toIntOrNull()
            if (id != null) map[token] = id
        }
        require(map.isNotEmpty()) { "No se pudo leer qwen_vocab.txt" }
        return map
    }

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
                if (id != null) map[parts[0]] = id
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
            if (line.isEmpty() || line.startsWith("#")) return@forEach
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
