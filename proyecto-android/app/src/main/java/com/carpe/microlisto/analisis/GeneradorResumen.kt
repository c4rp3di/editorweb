package com.carpe.microlisto.analisis

/**
 * Genera un resumen textual de una conversación a partir de sus métricas.
 * Sin IA: son reglas simples que producen un párrafo legible.
 */
object GeneradorResumen {

    fun generar(m: MetricasConversacion): String {
        if (m.numHablantes == 0) return "Sin datos suficientes para generar un resumen."

        val sb = StringBuilder()

        // Cabecera
        sb.append("Duración: ").append(formatearDuracion(m.duracionMs))
        sb.append(". ").append(m.numHablantes)
        if (m.numHablantes == 1) sb.append(" hablante detectado.")
        else sb.append(" hablantes detectados.")
        sb.append("\n\n")

        // Tiempo por hablante
        val total = m.tiempoPorHablante.values.sum().coerceAtLeast(1L)
        val ordenados = m.tiempoPorHablante.toList().sortedByDescending { it.second }

        for ((hablante, tiempo) in ordenados) {
            val pct = (tiempo * 100.0 / total).toInt()
            val turnos = m.turnosPorHablante[hablante] ?: 0
            val palabras = m.palabrasPorHablante[hablante] ?: 0
            sb.append("• Hablante ${hablante + 1}: ")
            sb.append("$pct% del tiempo, $turnos turnos, $palabras palabras")
            sb.append("\n")
        }
        sb.append("\n")

        // Quién habló más
        val masActivo = ordenados.firstOrNull()
        if (masActivo != null) {
            val pct = (masActivo.second * 100.0 / total).toInt()
            sb.append("Hablante más activo: #${masActivo.first + 1} ($pct%).\n")
        }

        // Quién inició más turnos
        if (m.iniciadorPorTurno.isNotEmpty()) {
            val conteoInicios = m.iniciadorPorTurno.groupingBy { it }.eachCount()
            val quienMasInicia = conteoInicios.maxByOrNull { it.value }
            if (quienMasInicia != null) {
                sb.append("Hablante que inicia más turnos: #${quienMasInicia.key + 1} (${quienMasInicia.value} veces).\n")
            }
        }

        // Interrupciones
        if (m.interrupciones.isEmpty()) {
            sb.append("Sin interrupciones detectadas.\n")
        } else {
            sb.append("Interrupciones totales: ${m.interrupciones.size}.\n")
            // Quién corta más
            val cortesPorHablante = m.interrupciones.groupingBy { it.hablanteQueCorta }.eachCount()
            val quienCortaMas = cortesPorHablante.maxByOrNull { it.value }
            if (quienCortaMas != null) {
                sb.append("Hablante que más interrumpe: #${quienCortaMas.key + 1} (${quienCortaMas.value} veces).\n")
            }
            // Par más común
            val parMasComun = m.matrizInterrupciones.maxByOrNull { it.value }
            if (parMasComun != null) {
                sb.append("Interrupción más frecuente: #${parMasComun.key.first + 1} → #${parMasComun.key.second + 1} (${parMasComun.value} veces).\n")
            }
        }

        // Silencios
        if (m.silencios.isNotEmpty()) {
            val durTotalSilencio = m.silencios.sumOf { it.duracionMs }
            val pctSilencio = (durTotalSilencio * 100.0 / m.duracionMs.coerceAtLeast(1L)).toInt()
            sb.append("Silencios largos: ${m.silencios.size} (total ${formatearDuracion(durTotalSilencio)}, ${pctSilencio}% del tiempo).\n")
        }

        // Ritmo de habla
        val totalPalabras = m.palabrasPorHablante.values.sum()
        val minutos = m.duracionMs / 60000.0
        if (minutos > 0.5 && totalPalabras > 0) {
            val ppm = (totalPalabras / minutos).toInt()
            sb.append("Ritmo medio: $ppm palabras por minuto.\n")
        }

        return sb.toString().trim()
    }

    private fun formatearDuracion(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val seg = s % 60
        return if (h > 0) String.format("%d h %d min", h, m)
               else if (m > 0) String.format("%d min %d s", m, seg)
               else String.format("%d s", seg)
    }
}