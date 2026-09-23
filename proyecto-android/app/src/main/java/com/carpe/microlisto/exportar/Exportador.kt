package com.carpe.microlisto.exportar

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.carpe.microlisto.analisis.AnalizadorConversacion
import com.carpe.microlisto.data.Conversacion
import com.carpe.microlisto.data.Segmento
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Exportador {

    fun generarTxt(
        context: Context,
        conversacion: Conversacion,
        segmentos: List<Segmento>
    ): Uri? {
        return try {
            val contenido = construirContenidoTxt(conversacion, segmentos)
            val formatoFecha = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val nombreArchivo = "microlisto_${formatoFecha.format(Date(conversacion.fechaMs))}.txt"
            val archivo = File(context.cacheDir, "export").apply { mkdirs() }.let { File(it, nombreArchivo) }
            archivo.writeText(contenido, Charsets.UTF_8)
            FileProvider.getUriForFile(context, "com.carpe.microlisto.fileprovider", archivo)
        } catch (e: Exception) {
            null
        }
    }

    fun generarJson(
        context: Context,
        conversacion: Conversacion,
        segmentos: List<Segmento>
    ): Uri? {
        return try {
            val contenido = construirContenidoJson(conversacion, segmentos)
            val formatoFecha = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val nombreArchivo = "microlisto_${formatoFecha.format(Date(conversacion.fechaMs))}.json"
            val archivo = File(context.cacheDir, "export").apply { mkdirs() }.let { File(it, nombreArchivo) }
            archivo.writeText(contenido, Charsets.UTF_8)
            FileProvider.getUriForFile(context, "com.carpe.microlisto.fileprovider", archivo)
        } catch (e: Exception) {
            null
        }
    }

    fun compartirTxt(
        context: Context,
        conversacion: Conversacion,
        segmentos: List<Segmento>
    ): Boolean {
        val uri = generarTxt(context, conversacion, segmentos) ?: return false
        return lanzarCompartir(context, uri, "text/plain", "Compartir conversación")
    }

    fun compartirJson(
        context: Context,
        conversacion: Conversacion,
        segmentos: List<Segmento>
    ): Boolean {
        val uri = generarJson(context, conversacion, segmentos) ?: return false
        return lanzarCompartir(context, uri, "application/json", "Compartir conversación (JSON)")
    }

    private fun lanzarCompartir(context: Context, uri: Uri, mime: String, titulo: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, titulo)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun construirContenidoTxt(
        conversacion: Conversacion,
        segmentos: List<Segmento>
    ): String {
        val sb = StringBuilder()
        val formatoFecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        sb.append("MICROLISTO — Transcripción de conversación\n")
        sb.append("========================================\n\n")
        sb.append("Fecha: ${formatoFecha.format(Date(conversacion.fechaMs))}\n")
        sb.append("Duración: ${formatearDuracion(conversacion.duracionMs)}\n")
        sb.append("Hablantes detectados: ${conversacion.numHablantes}\n\n")

        // Métricas detalladas si hay segmentos
        if (segmentos.isNotEmpty()) {
            val metricas = AnalizadorConversacion.analizar(segmentos, conversacion.duracionMs)
            val total = metricas.tiempoPorHablante.values.sum().coerceAtLeast(1L)
            sb.append("MÉTRICAS\n--------\n")
            for ((h, t) in metricas.tiempoPorHablante.toList().sortedByDescending { it.second }) {
                val pct = (t * 100.0 / total).toInt()
                val turnos = metricas.turnosPorHablante[h] ?: 0
                sb.append("• Hablante ${h + 1}: $pct% (${formatearDuracion(t)}, $turnos turnos)\n")
            }
            if (metricas.interrupciones.isNotEmpty()) {
                sb.append("Interrupciones: ${metricas.interrupciones.size}\n")
            }
            if (metricas.silencios.isNotEmpty()) {
                val durSil = metricas.silencios.sumOf { it.duracionMs }
                sb.append("Silencios: ${metricas.silencios.size} (total ${formatearDuracion(durSil)})\n")
            }
            sb.append("\n")
        }

        if (conversacion.resumen.isNotBlank()) {
            sb.append("RESUMEN\n-------\n${conversacion.resumen}\n\n")
        }

        sb.append("TRANSCRIPCIÓN\n-------------\n\n")
        val conTexto = segmentos.filter { it.texto.isNotBlank() }
        if (conTexto.isEmpty()) {
            sb.append(conversacion.transcripcion).append("\n")
        } else {
            for (s in conTexto) {
                sb.append("[${formatearMs(s.inicioMs)}] Hablante ${s.hablanteId + 1}: ${s.texto}\n\n")
            }
        }

        sb.append("\n---\nGenerado por Microlisto\n")
        return sb.toString()
    }

    private fun construirContenidoJson(
        conversacion: Conversacion,
        segmentos: List<Segmento>
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"id\": ${conversacion.id},\n")
        sb.append("  \"titulo\": ${jsonString(conversacion.titulo)},\n")
        sb.append("  \"fechaMs\": ${conversacion.fechaMs},\n")
        sb.append("  \"duracionMs\": ${conversacion.duracionMs},\n")
        sb.append("  \"numHablantes\": ${conversacion.numHablantes},\n")
        sb.append("  \"transcripcion\": ${jsonString(conversacion.transcripcion)},\n")
        sb.append("  \"resumen\": ${jsonString(conversacion.resumen)},\n")

        if (segmentos.isNotEmpty()) {
            val metricas = AnalizadorConversacion.analizar(segmentos, conversacion.duracionMs)
            sb.append("  \"metricas\": {\n")
            sb.append("    \"numInterrupciones\": ${metricas.interrupciones.size},\n")
            sb.append("    \"numSilencios\": ${metricas.silencios.size},\n")
            sb.append("    \"tiempoPorHablanteMs\": {")
            val entradasTiempo = metricas.tiempoPorHablante.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }
            sb.append(entradasTiempo).append("},\n")
            sb.append("    \"turnosPorHablante\": {")
            val entradasTurnos = metricas.turnosPorHablante.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }
            sb.append(entradasTurnos).append("}\n")
            sb.append("  },\n")
        }

        sb.append("  \"segmentos\": [\n")
        segmentos.forEachIndexed { i, s ->
            sb.append("    {\n")
            sb.append("      \"hablanteId\": ${s.hablanteId},\n")
            sb.append("      \"inicioMs\": ${s.inicioMs},\n")
            sb.append("      \"finMs\": ${s.finMs},\n")
            sb.append("      \"texto\": ${jsonString(s.texto)}\n")
            sb.append("    }")
            if (i < segmentos.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    private fun formatearMs(ms: Long): String {
        val totalSeg = ms / 1000
        val min = totalSeg / 60
        val seg = totalSeg % 60
        return String.format("%d:%02d", min, seg)
    }

    private fun formatearDuracion(ms: Long): String {
        val totalSeg = ms / 1000
        val horas = totalSeg / 3600
        val minutos = (totalSeg % 3600) / 60
        val segundos = totalSeg % 60
        return if (horas > 0) String.format("%d:%02d:%02d", horas, minutos, segundos)
               else String.format("%d:%02d", minutos, segundos)
    }
}