package com.perplexus.game.levels

/**
 * Parser/serializador JSON mínimo y sin dependencias (funciona en JVM puro, sin org.json).
 * Valores: Map<String, Any?>, List<Any?>, String, Double, Boolean, null.
 */
object Json {
    class JsonException(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Contenido sobrante en posición ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> {
        val v = parse(text)
        if (v is Map<*, *>) return v as Map<String, Any?>
        throw JsonException("Se esperaba un objeto JSON en la raíz")
    }

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd(): Boolean = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

        fun readValue(): Any? {
            skipWs()
            if (atEnd()) throw JsonException("Fin inesperado")
            return when (val c = peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBool()
                'n' -> readNull()
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("Carácter inesperado '$c' en $pos")
            }
        }

        fun readObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (peek() != '"') throw JsonException("Se esperaba clave en $pos")
                val key = readString()
                skipWs()
                if (peek() != ':') throw JsonException("Se esperaba ':' en $pos")
                pos++
                map[key] = readValue()
                skipWs()
                val c = peek()
                pos++
                if (c == ',') continue
                if (c == '}') break
                throw JsonException("Se esperaba ',' o '}' en ${pos - 1}")
            }
            return map
        }

        fun readArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                list.add(readValue())
                skipWs()
                val c = peek()
                pos++
                if (c == ',') continue
                if (c == ']') break
                throw JsonException("Se esperaba ',' o ']' en ${pos - 1}")
            }
            return list
        }

        fun readString(): String {
            val sb = StringBuilder()
            pos++ // "
            while (true) {
                if (atEnd()) throw JsonException("Cadena sin cerrar")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("Escape incompleto")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Escape unicode incompleto")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Escape inválido \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun readBool(): Boolean {
            if (s.startsWith("true", pos)) { pos += 4; return true }
            if (s.startsWith("false", pos)) { pos += 5; return false }
            throw JsonException("Literal inválido en $pos")
        }

        fun readNull(): Any? {
            if (s.startsWith("null", pos)) { pos += 4; return null }
            throw JsonException("Literal inválido en $pos")
        }

        fun readNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw JsonException("Número inválido en $start")
        }
    }

    // --------------------------------------------------------------- Serialización
    fun stringify(v: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, v, pretty, 0)
        return sb.toString()
    }

    private fun indent(sb: StringBuilder, n: Int) { for (i in 0 until n) sb.append("  ") }

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is Float -> sb.append(if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString())
            is Double -> sb.append(if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); indent(sb, depth + 1) }
                    writeString(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    write(sb, x, pretty, depth + 1)
                }
                if (pretty && v.isNotEmpty()) { sb.append('\n'); indent(sb, depth) }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); indent(sb, depth + 1) }
                    write(sb, x, pretty, depth + 1)
                }
                if (pretty && v.isNotEmpty()) { sb.append('\n'); indent(sb, depth) }
                sb.append(']')
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    // --------------------------------------------------------------- Accesores tipados
    @Suppress("UNCHECKED_CAST")
    fun obj(m: Map<String, Any?>, key: String): Map<String, Any?>? = m[key] as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    fun arr(m: Map<String, Any?>, key: String): List<Any?>? = m[key] as? List<Any?>
    fun num(m: Map<String, Any?>, key: String, def: Float): Float = (m[key] as? Number)?.toFloat() ?: def
    fun str(m: Map<String, Any?>, key: String, def: String): String = (m[key] as? String) ?: def
    fun bool(m: Map<String, Any?>, key: String, def: Boolean): Boolean = (m[key] as? Boolean) ?: def
}
