package com.carpe.panoptes.analyzer.orchestrator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Progreso en vivo de los análisis en curso (id de análisis -> porcentaje y fase actual).
 * Vive solo en memoria, a propósito: no toca el esquema de Room. Si el proceso muere, el
 * progreso se pierde y la pantalla muestra "esperando" hasta que el análisis se reanude o termine.
 */
object AnalysisProgressBus {

    data class Estado(val percent: Int, val fase: String, val actualizado: Long)

    private val _estado = MutableStateFlow<Map<String, Estado>>(emptyMap())
    val estado: StateFlow<Map<String, Estado>> = _estado.asStateFlow()

    fun report(analysisId: String, percent: Int, fase: String) {
        val nuevo = Estado(percent.coerceIn(0, 100), fase, System.currentTimeMillis())
        _estado.value = _estado.value + (analysisId to nuevo)
    }

    fun clear(analysisId: String) {
        _estado.value = _estado.value - analysisId
    }
}
