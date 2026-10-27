
package com.lab.netlens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Export {
    private fun q(s: String): String {
        var v = s.replace("\r", " ").replace("\n", " | ")
        if (v.isNotEmpty() && (v[0] == '=' || v[0] == '+' || v[0] == '-' || v[0] == '@')) v = "'" + v
        return "\"" + v.replace("\"", "\"\"") + "\""
    }
    private fun baseDomain(h: String): String {
        if (h.isEmpty()) return ""
        if (h.all { it.isDigit() || it == '.' }) return "(IP)"
        val p = h.split('.')
        return if (p.size <= 2) h else p[p.size - 2] + "." + p[p.size - 1]
    }
    fun build(flows: List<Flow>, raw: Boolean): String {
        val sb = StringBuilder("\uFEFF")
        val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val minute = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        if (raw) sb.append("inicio,app,paquete,protocolo,ip_destino,puerto,nombre,categoria,bytes_subidos,bytes_bajados,duracion_s,mitm,notas\n")
        else sb.append("minuto,app,protocolo,puerto,dominio_base,categoria,subida_aprox_KB,bajada_aprox_KB,http_sin_cifrar,tls,mitm\n")
        for (f in flows) {
            val notes = synchronized(f.notes) { ArrayList(f.notes) }
            val name = f.name(); val cat = Dpi.category(name) ?: ""
            if (raw) {
                sb.append(q(full.format(Date(f.start)))).append(',').append(q(f.stat.label)).append(',').append(q(f.stat.pkg)).append(',')
                    .append(q(f.proto)).append(',').append(q(f.dstIp)).append(',').append(f.dstPort).append(',').append(q(name)).append(',')
                    .append(q(cat)).append(',').append(f.up.get()).append(',').append(f.down.get()).append(',').append((f.last - f.start) / 1000).append(',')
                    .append(if (f.mitm) "si" else "no").append(',').append(q(notes.joinToString(" | "))).append('\n')
            } else {
                sb.append(q(minute.format(Date(f.start)))).append(',').append(q(f.stat.label)).append(',').append(q(f.proto)).append(',')
                    .append(f.dstPort).append(',').append(q(baseDomain(name))).append(',').append(q(cat)).append(',')
                    .append(f.up.get() / 1024).append(',').append(f.down.get() / 1024).append(',')
                    .append(if (notes.any { it.startsWith("⚠ HTTP") }) "si" else "no").append(',')
                    .append(if (notes.any { it.startsWith("🔒") }) "si" else "no").append(',')
                    .append(if (f.mitm) "si" else "no").append('\n')
            }
        }
        return sb.toString()
    }
}

