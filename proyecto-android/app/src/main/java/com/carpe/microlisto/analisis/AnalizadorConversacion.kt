package com.carpe.microlisto.analisis

import com.carpe.microlisto.data.Segmento

data class MetricasConversacion(
    val duracionMs: Long,
    val numHablantes: Int,
    val tiempoPorHablante: Map<Int, Long>,
    val turnosPorHablante: Map<Int, Int>,
    val iniciadorPorTurno: List<Int>,
    val interrupciones: List<Interrupcion>,
    val matrizInterrupciones: Map<Pair<Int, Int>, Int>,
    val silencios: List<Silencio>,
    val palabrasPorHablante: Map<Int, Int>
)

data class Interrupcion(
    val hablanteQueCorta: Int,
    val hablanteCortado: Int,
    val posicionMs: Long
)

data class Silencio(
    val inicioMs: Long,
    val finMs: Long,
    val duracionMs: Long
)

/**
 * Calcula métricas conversacionales a partir de los segmentos etiquetados
 * por hablante. Todo se hace sobre la lista de segmentos, sin tocar audio.
 */
object AnalizadorConversacion {

    // Un cambio de turno con solape se considera interrupción.
    // Dos segmentos se solapan si el siguiente empieza mientras el anterior
    // sigue activo, con al menos este margen en ms.
    private const val MARGEN_INTERRUPCION_MS = 200L

    // Silencio mínimo para contarlo como tal
    private const val SILENCIO_MIN_MS = 1500L

    fun analizar(segmentos: List<Segmento>, duracionMs: Long): MetricasConversacion {
        if (segmentos.isEmpty()) {
            return MetricasConversacion(
                duracionMs = duracionMs,
                numHablantes = 0,
                tiempoPorHablante = emptyMap(),
                turnosPorHablante = emptyMap(),
                iniciadorPorTurno = emptyList(),
                interrupciones = emptyList(),
                matrizInterrupciones = emptyMap(),
                silencios = emptyList(),
                palabrasPorHablante = emptyMap()
            )
        }

        val ordenados = segmentos.sortedBy { it.inicioMs }

        val tiempoPorHablante = mutableMapOf<Int, Long>()
        val turnosPorHablante = mutableMapOf<Int, Int>()
        val palabrasPorHablante = mutableMapOf<Int, Int>()
        val iniciadorPorTurno = mutableListOf<Int>()
        val interrupciones = mutableListOf<Interrupcion>()
        val matrizInterrupciones = mutableMapOf<Pair<Int, Int>, Int>()
        val silencios = mutableListOf<Silencio>()

        var hablanteAnterior: Int? = null
        var finAnterior: Long = 0

        for (s in ordenados) {
            val dur = s.finMs - s.inicioMs
            tiempoPorHablante[s.hablanteId] = (tiempoPorHablante[s.hablanteId] ?: 0L) + dur
            turnosPorHablante[s.hablanteId] = (turnosPorHablante[s.hablanteId] ?: 0) + 1
            val nPalabras = s.texto.split(Regex("\\s+")).count { it.isNotBlank() }
            palabrasPorHablante[s.hablanteId] = (palabrasPorHablante[s.hablanteId] ?: 0) + nPalabras

            if (hablanteAnterior == null || hablanteAnterior != s.hablanteId) {
                iniciadorPorTurno.add(s.hablanteId)
            }

            // Detectar interrupción: el hablante actual empieza antes de que
            // termine el hablante anterior Y son distintos.
            if (hablanteAnterior != null && hablanteAnterior != s.hablanteId) {
                val solape = finAnterior - s.inicioMs
                if (solape >= MARGEN_INTERRUPCION_MS) {
                    interrupciones.add(
                        Interrupcion(
                            hablanteQueCorta = s.hablanteId,
                            hablanteCortado = hablanteAnterior,
                            posicionMs = s.inicioMs
                        )
                    )
                    val clave = hablanteAnterior to s.hablanteId
                    matrizInterrupciones[clave] = (matrizInterrupciones[clave] ?: 0) + 1
                }
            }

            // Detectar silencio entre el fin del anterior y el inicio del actual
            if (finAnterior > 0 && s.inicioMs - finAnterior >= SILENCIO_MIN_MS) {
                silencios.add(
                    Silencio(
                        inicioMs = finAnterior,
                        finMs = s.inicioMs,
                        duracionMs = s.inicioMs - finAnterior
                    )
                )
            }

            hablanteAnterior = s.hablanteId
            finAnterior = maxOf(finAnterior, s.finMs)
        }

        return MetricasConversacion(
            duracionMs = duracionMs,
            numHablantes = tiempoPorHablante.size,
            tiempoPorHablante = tiempoPorHablante,
            turnosPorHablante = turnosPorHablante,
            iniciadorPorTurno = iniciadorPorTurno,
            interrupciones = interrupciones,
            matrizInterrupciones = matrizInterrupciones,
            silencios = silencios,
            palabrasPorHablante = palabrasPorHablante
        )
    }
}