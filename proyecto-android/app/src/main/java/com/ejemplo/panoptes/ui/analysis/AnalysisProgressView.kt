package com.carpe.panoptes.ui.analysis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carpe.panoptes.analyzer.orchestrator.AnalysisProgressBus
import com.carpe.panoptes.core.db.AnalysisEntity
import com.carpe.panoptes.core.db.AnalysisStatus
import kotlinx.coroutines.delay

/** ¿Sigue el análisis sin terminar? (PENDIENTE o EN_CURSO) */
fun AnalysisEntity.enProceso(): Boolean = status == AnalysisStatus.PENDIENTE || status == AnalysisStatus.EN_CURSO

private fun mmss(segundos: Long): String {
    val s = segundos.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Barra de progreso con porcentaje, fase actual, tiempo transcurrido y "última actividad hace Ns",
 * para ver de un vistazo si el análisis avanza o está atascado. No pinta nada si el análisis ya terminó.
 */
@Composable
fun ProgresoAnalisis(item: AnalysisEntity, progreso: AnalysisProgressBus.Estado?, modifier: Modifier = Modifier) {
    if (!item.enProceso()) return
    var ahora by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            ahora = System.currentTimeMillis()
        }
    }
    val transcurrido = mmss((ahora - item.startedAt) / 1000)
    Column(modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (progreso != null) {
            LinearProgressIndicator(progress = progreso.percent / 100f, modifier = Modifier.fillMaxWidth())
            Text(
                "${progreso.percent}% · ${progreso.fase}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Tiempo: $transcurrido · última actividad hace ${((ahora - progreso.actualizado) / 1000).coerceAtLeast(0)} s",
                style = MaterialTheme.typography.labelSmall
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Esperando a que empiece… · Tiempo: $transcurrido", style = MaterialTheme.typography.bodySmall)
        }
    }
}
