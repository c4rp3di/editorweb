package com.carpe.microlisto.exportar

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.carpe.microlisto.data.Conversacion
import com.carpe.microlisto.data.Segmento
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Genera un archivo de texto con la transcripción y las métricas de una
 * conversación, y devuelve el Uri para compartirlo o abrirlo con otra app.
 */
object Exportador {

    fun generarTxt(
        context: Context,
        conversacion: Conversacion,
        segmentos: List<Segmento>
    ): Uri? {
        return try {
            val contenido = construirContenido(conversacion, segmentos)
            val dirExport = File(context.cacheDir, "export").apply { mkdirs() }
            val formatoFecha = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val nombreArchivo = "microlisto_${formatoFecha.format(Date(conversacion.fechaMs))}.txt"
            val archivo = File(dirExport, nombreArchivo)
            archivo.writeText(contenido, Charsets.UTF_8)

            FileProvider.getUriForFile(
                context,
                "com.carpe.microlisto.fileprovider",
                archivo
            )
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
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Compartir conversación")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return true
    }

    private fun construirContenido(
        conversacion: Conversacion,
        segmentos: List<Segmento>
    ): String {
        val sb = StringBuilder()
        val formatoFecha = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        sb.append("MICROLISTO — Transcripción de conversación\n")
        sb.append("========================================\n\n")
        sb.append("Fecha: ${formatoFecha.format(Date(conversacion.fechaMs))}\n")
        sb.append("Duración: ${formatearDuracion(conversacion.duracionMs)}\n")
        sb.append("Hablantes detectados: ${conversacion.numHablantes}\n")
        sb.append("\n")

        // Métricas
        if (segmentos.isNotEmpty()) {
            sb.append("MÉTRICAS\n")
            sb.append("--------\n")
            val tiempoPorHablante = mutableMapOf<Int, Long>()
            val turnosPorHablante = mutableMapOf<Int, Int>()
            for (s in segmentos) {
                val dur = s.finMs - s.inicioMs
                tiempoPorHablante[s.hablanteId] = (tiempoPorHablante[s.hablanteId] ?: 0L) + dur
                turnosPorHablante[s.hablanteId] = (turnosPorHablante[s.hablanteId] ?: 0) + 1
            }
            val total = tiempoPorHablante.values.sum().coerceAtLeast(1L)
            for ((hablante, tiempo) in tiempoPorHablante.toList().sortedByDescending { it.second }) {
                val porcentaje = (tiempo * 100.0 / total).toInt()
                val turnos = turnosPorHablante[hablante] ?: 0
                sb.append("• Hablante ${hablante + 1}: ${porcentaje}% (${formatearDuracion(tiempo)}, $turnos turnos)\n")
            }
            sb.append("\n")
        }

        // Transcripción
        sb.append("TRANSCRIPCIÓN\n")
        sb.append("-------------\n\n")

        val conTexto = segmentos.filter { it.texto.isNotBlank() }
        if (conTexto.isEmpty()) {
            sb.append(conversacion.transcripcion)
            sb.append("\n")
        } else {
            for (s in conTexto) {
                val hora = formatearMs(s.inicioMs)
                sb.append("[$hora] Hablante ${s.hablanteId + 1}: ${s.texto}\n\n")
            }
        }

        sb.append("\n---\n")
        sb.append("Generado por Microlisto\n")
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