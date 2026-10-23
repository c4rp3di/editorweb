package com.luzcontrol.app.ui.consumption

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luzcontrol.app.domain.model.PowerRecommendation
import com.luzcontrol.app.ui.components.BarEntry
import com.luzcontrol.app.ui.components.BarLineChart
import com.luzcontrol.app.ui.components.Fmt
import com.luzcontrol.app.ui.components.NoticeBanner
import com.luzcontrol.app.ui.components.SectionCard
import com.luzcontrol.app.ui.components.StateHost
import com.luzcontrol.app.ui.components.StatRow
import com.luzcontrol.app.ui.theme.EnergyColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun ConsumptionScreen(
    onOpenSettings: () -> Unit,
    viewModel: ConsumptionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    var pickerStep by remember { mutableIntStateOf(0) } // 0 = cerrado, 1 = desde, 2 = hasta
    var pickedFrom by remember { mutableStateOf<LocalDate?>(null) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RangePreset.values().toList()) { p ->
                    FilterChip(
                        selected = range.preset == p,
                        onClick = {
                            if (p == RangePreset.CUSTOM) pickerStep = 1 else viewModel.selectPreset(p)
                        },
                        label = { Text(p.label) }
                    )
                }
            }
        }
        item {
            StateHost(state = state, onRetry = {}, emptyAction = "Ir a Ajustes", onEmptyAction = onOpenSettings) { ui ->
                ConsumptionBody(ui)
            }
        }
    }

    if (pickerStep == 1) {
        DatePickerStep(
            title = "Desde",
            initial = range.customFrom ?: LocalDate.now().minusDays(29),
            onDismiss = { pickerStep = 0 },
            onPick = { pickedFrom = it; pickerStep = 2 }
        )
    }
    if (pickerStep == 2) {
        DatePickerStep(
            title = "Hasta",
            initial = range.customTo ?: LocalDate.now(),
            onDismiss = { pickerStep = 0 },
            onPick = { to ->
                viewModel.setCustom(pickedFrom ?: to, to)
                pickerStep = 0
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerStep(title: String, initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, top = 16.dp))
        DatePicker(state = state)
    }
}

@Composable
private fun ConsumptionBody(ui: ConsumptionUi) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Consumo y precio", subtitle = "${ui.rangeLabel} · ${ui.granularity.label}") {
            BarLineChart(bars = ui.bars, line = ui.priceLine)
            Text(
                "Superpuesto: precio PVPC (€/kWh) para correlacionar consumo y coste.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SectionCard("Totales del periodo") {
            StatRow("Consumo total", Fmt.kwh(ui.totalKwh), emphasized = true)
            StatRow("Coste de energía (PVPC)", ui.totalCostEur?.let { Fmt.eur(it) } ?: "sin precios")
            StatRow("Precio medio ponderado", ui.avgPrice?.let { Fmt.price(it) } ?: "—")
            if (ui.surplusKwh > 0.0) StatRow("Excedentes vertidos", Fmt.kwh(ui.surplusKwh))
        }
        AnomaliesCard(ui)
        StandbyCard(ui)
        PowerCard(ui)
    }
}

@Composable
private fun AnomaliesCard(ui: ConsumptionUi) {
    SectionCard("Picos inusuales", subtitle = "Frente a tu media de las 4 semanas anteriores a la misma hora") {
        if (ui.anomalies.isEmpty()) {
            Text("No se han detectado picos inusuales en este periodo.")
        } else {
            val zone = com.luzcontrol.app.domain.model.TariffCalendar.ZONE
            ui.anomalies.forEach { a ->
                val z = a.start.atZone(zone)
                Text(
                    "Pico inusual el ${z.format(DateTimeFormatter.ofPattern("dd/MM"))} a las " +
                        "${String.format(Fmt.es, "%02d:00", z.hour)} (${Fmt.kwh(a.kwh)}, +${Fmt.pct(a.excessPct)} sobre tu media)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun StandbyCard(ui: ConsumptionUi) {
    SectionCard("Consumo en reposo (standby)", subtitle = "Estimado con la curva nocturna (02:00–06:00)") {
        val s = ui.standby
        if (s == null) {
            Text("Hacen falta al menos 5 noches con datos para estimarlo.")
        } else {
            StatRow("Consumo base nocturno", Fmt.watts(s.baseWatts), emphasized = true)
            StatRow("Equivale a", Fmt.kwh1(s.monthlyKwh) + " al mes")
            s.monthlyCostEur?.let { StatRow("Coste aproximado", Fmt.eur(it) + " al mes") }
            if (s.high) {
                NoticeBanner(
                    "Tu consumo base es alto. Revisa aparatos en espera (televisores, consolas, cargadores, " +
                        "routers extra, termo o nevera antigua).",
                    isError = true
                )
            } else {
                Text("Tu consumo base está en un rango normal.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PowerCard(ui: ConsumptionUi) {
    SectionCard("Potencia máxima demandada", subtitle = "Maxímetro mensual frente a la potencia contratada") {
        val p = ui.power
        if (p.months.isEmpty() || p.contractedP1Kw == null) {
            Text("Aún no hay lecturas de maxímetro o de potencia contratada.")
        } else {
            val fmt = DateTimeFormatter.ofPattern("MM/yy")
            val contracted = p.contractedP1Kw
            BarLineChart(
                bars = p.months.map {
                    BarEntry(
                        it.month.format(fmt), it.maxKwP1,
                        if (it.maxKwP1 >= contracted * 0.98) EnergyColors.Expensive else null
                    )
                },
                line = p.months.map { contracted },
                barUnit = "kW",
                lineUnit = "kW",
                lineDecimals = 2,
                sharedAxis = true,
                barLegend = "Máxima demandada (kW)",
                lineLegend = "Contratada P1 (${Fmt.kw(contracted)})",
                height = 180.dp
            )
            when (p.recommendation) {
                PowerRecommendation.OVERSIZED -> NoticeBanner(
                    "Tu potencia contratada parece sobredimensionada: el máximo registrado no supera el 60 % de " +
                        "${Fmt.kw(contracted)}. Podrías valorar bajarla a unos ${p.suggestedP1Kw?.let { Fmt.kw(it) } ?: "—"} " +
                        "y ahorrar en el término de potencia."
                )
                PowerRecommendation.UNDERSIZED -> NoticeBanner(
                    "Has llegado a tu potencia contratada. Si salta el ICP con frecuencia, valora subirla.",
                    isError = true
                )
                else -> Text("La potencia contratada parece adecuada a tu uso.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
