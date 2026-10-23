package com.luzcontrol.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luzcontrol.app.domain.model.DashboardData
import com.luzcontrol.app.domain.model.TariffPeriod
import com.luzcontrol.app.ui.components.DonutChart
import com.luzcontrol.app.ui.components.DonutSlice
import com.luzcontrol.app.ui.components.Fmt
import com.luzcontrol.app.ui.components.NoticeBanner
import com.luzcontrol.app.ui.components.SectionCard
import com.luzcontrol.app.ui.components.StateHost
import com.luzcontrol.app.ui.components.StatRow
import com.luzcontrol.app.ui.theme.EnergyColors

@Composable
fun HomeScreen(
    syncing: Boolean,
    onSync: () -> Unit,
    onOpenSimulator: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StateHost(
        state = state,
        onRetry = onSync,
        emptyAction = "Ir a Ajustes",
        onEmptyAction = onOpenSettings
    ) { data ->
        HomeContent(data, syncing, onSync, onOpenSimulator)
    }
}

@Composable
private fun HomeContent(
    data: DashboardData,
    syncing: Boolean,
    onSync: () -> Unit,
    onOpenSimulator: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SyncCard(data, syncing, onSync) }
        item { DayCard(data) }
        item { PeriodCard(data) }
        item { PowerCard(data) }
        item {
            SectionCard("Acciones rápidas") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSync, enabled = !syncing) { Text("Sincronizar ahora") }
                    OutlinedButton(onClick = onOpenSimulator) { Text("Simulador de factura") }
                }
            }
        }
    }
}

@Composable
private fun SyncCard(data: DashboardData, syncing: Boolean, onSync: () -> Unit) {
    SectionCard("Estado de sincronización") {
        val last = data.lastSync?.let { "Última actualización: ${Fmt.ago(it)}" } ?: "Aún no se ha sincronizado"
        val until = data.dataUntil?.let { " · Datos hasta el ${Fmt.shortDate(it)}" }.orEmpty()
        Text(last + until, style = MaterialTheme.typography.bodyMedium)
        if (syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (data.lastSyncOk == false && !data.lastSyncMessage.isNullOrBlank()) {
            NoticeBanner(data.lastSyncMessage, isError = true)
        }
        Text(
            "Los datos de la distribuidora llegan con 1–2 días de retraso; no son en tiempo real.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DayCard(data: DashboardData) {
    val day = data.referenceDay?.let { Fmt.date(it) } ?: "—"
    SectionCard("Resumen del día", subtitle = "Último día con datos: $day") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(data.dayKwh?.let { Fmt.kwh(it) } ?: "—", style = MaterialTheme.typography.headlineSmall)
                Text("consumidos", style = MaterialTheme.typography.bodySmall)
            }
            Column {
                Text(data.dayCostEur?.let { Fmt.eur(it) } ?: "—", style = MaterialTheme.typography.headlineSmall)
                Text("coste estimado", style = MaterialTheme.typography.bodySmall)
            }
        }
        StatRow("Precio medio ponderado", data.dayAvgPrice?.let { Fmt.price(it) } ?: "sin precios")
        StatRow("Consumo del mes en curso", Fmt.kwh1(data.monthKwh))
        if (data.dayPartialPrices) {
            Text(
                "Faltan precios para algunas horas: el coste es aproximado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PeriodCard(data: DashboardData) {
    val total = data.periodKwh.values.sum()
    SectionCard("Reparto por periodos", subtitle = "Punta · Llano · Valle (2.0TD)") {
        if (total <= 0.0) {
            Text("Sin datos para repartir.")
        } else {
            DonutChart(
                slices = listOf(
                    DonutSlice("Punta", data.periodKwh[TariffPeriod.P1] ?: 0.0, EnergyColors.Punta),
                    DonutSlice("Llano", data.periodKwh[TariffPeriod.P2] ?: 0.0, EnergyColors.Llano),
                    DonutSlice("Valle", data.periodKwh[TariffPeriod.P3] ?: 0.0, EnergyColors.Valle)
                ),
                centerText = Fmt.kwh1(total)
            )
        }
    }
}

@Composable
private fun PowerCard(data: DashboardData) {
    SectionCard("Potencia") {
        val max = data.maxPowerMonthKw
        val contracted = data.contractedKw
        if (max == null || contracted == null || contracted <= 0.0) {
            Text(
                if (contracted == null) "Aún no hay datos de potencia contratada."
                else "Aún no hay lecturas de maxímetro este mes.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            val ratio = (max / contracted).toFloat().coerceIn(0f, 1f)
            Text(
                "Potencia máxima demandada este mes: ${Fmt.kw(max)} de ${Fmt.kw(contracted)} contratados",
                style = MaterialTheme.typography.bodyMedium
            )
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth(),
                color = if (ratio >= 0.95f) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
            )
            Text(Fmt.pct(ratio * 100.0) + " de tu potencia contratada", style = MaterialTheme.typography.bodySmall)
        }
    }
}
