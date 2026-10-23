package com.luzcontrol.app.ui.prices

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luzcontrol.app.domain.model.TariffCalendar
import com.luzcontrol.app.ui.components.Fmt
import com.luzcontrol.app.ui.components.LineChartView
import com.luzcontrol.app.ui.components.NoticeBanner
import com.luzcontrol.app.ui.components.SectionCard
import com.luzcontrol.app.ui.components.StateHost
import com.luzcontrol.app.ui.components.StatRow
import com.luzcontrol.app.ui.theme.EnergyColors

private fun levelColor(l: PriceLevel): Color = when (l) {
    PriceLevel.CHEAP -> EnergyColors.Cheap
    PriceLevel.MEDIUM -> EnergyColors.Medium
    PriceLevel.EXPENSIVE -> EnergyColors.Expensive
}

@Composable
fun PricesScreen(onSync: () -> Unit, viewModel: PricesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StateHost(state = state, onRetry = onSync, emptyAction = "Sincronizar", onEmptyAction = onSync) { ui ->
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { NowCard(ui) }
            item { DayCard("Hoy", ui.today) }
            if (ui.tomorrow.isNotEmpty()) item { DayCard("Mañana", ui.tomorrow) }
            else item {
                NoticeBanner("Los precios de mañana se publican hacia las 20:15. Sincroniza después para verlos.")
            }
            item { CalculatorCard(ui) }
            item { WindowCard(ui, onHours = viewModel::setWindowHours) }
            item { ShiftCard(ui) }
            item {
                SectionCard("Evolución del PVPC", subtitle = "Media diaria de los últimos 30 días") {
                    LineChartView(ui.historyLabels, ui.historyValues)
                }
            }
        }
    }
}

@Composable
private fun NowCard(ui: PricesUi) {
    SectionCard("Precio de la luz ahora") {
        val n = ui.now
        Text(n?.let { Fmt.price(it.priceEurKwh) } ?: "—", style = MaterialTheme.typography.headlineMedium)
        if (n != null) Text("${n.level.label} · periodo ${TariffCalendar.periodAt(n.start).label}")
        ui.cheapestToday?.let { StatRow("Hora más barata de hoy", "${Fmt.hour(it.start)} · ${Fmt.price(it.priceEurKwh)}") }
        ui.dearestToday?.let { StatRow("Hora más cara de hoy", "${Fmt.hour(it.start)} · ${Fmt.price(it.priceEurKwh)}") }
    }
}

@Composable
private fun DayCard(title: String, list: List<HourPrice>) {
    SectionCard("Precios de $title", subtitle = "Verde = barata · Ámbar = media · Rojo = cara") {
        list.forEach { h ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.size(12.dp).clip(CircleShape).background(levelColor(h.level))) {}
                Text(Fmt.hour(h.start), Modifier.weight(1f))
                Text(h.level.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(Fmt.price3(h.priceEurKwh) + " €/kWh")
            }
        }
    }
}

@Composable
private fun CalculatorCard(ui: PricesUi) {
    var kwhText by remember { mutableStateOf("1,5") }
    SectionCard("Calculadora de ahorro", subtitle = "Mueve un electrodoméstico de ahora a la hora más barata") {
        OutlinedTextField(
            value = kwhText,
            onValueChange = { kwhText = it },
            label = { Text("Consumo del aparato (kWh), p. ej. lavadora 1,5") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        val kwh = Fmt.parseDouble(kwhText)
        val current = ui.now ?: ui.futurePrices.firstOrNull()
        val cheapest = ui.futurePrices.minByOrNull { it.priceEurKwh }
        if (kwh == null || kwh <= 0.0 || current == null || cheapest == null) {
            Text("Introduce un consumo válido para calcular.")
        } else {
            val saving = kwh * (current.priceEurKwh - cheapest.priceEurKwh)
            if (saving <= 0.0001) Text("Ahora ya es de las horas más baratas de las próximas 24 h.")
            else Text(
                "Si lo mueves de las ${Fmt.hour(current.start)} a las ${Fmt.hour(cheapest.start)} ahorras ${Fmt.eur(saving)}.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun WindowCard(ui: PricesUi, onHours: (Int) -> Unit) {
    val context = LocalContext.current
    SectionCard("Mejor franja para tus aparatos", subtitle = "Franja continua más barata a partir de ahora") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..4).forEach { h ->
                FilterChip(selected = ui.windowHours == h, onClick = { onHours(h) }, label = { Text("$h h") })
            }
        }
        val w = ui.window
        if (w == null) {
            Text("No hay una franja continua de ${ui.windowHours} h con precios disponibles.")
        } else {
            val end = w.start.plusSeconds(w.hours * 3600L)
            Text(
                "De ${Fmt.dateTime(w.start)} a ${Fmt.hour(end)} · media ${Fmt.price(w.avgPriceEurKwh)}",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = {
                val intent = Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, "Luz barata: buen momento para lavadora/lavavajillas")
                    .putExtra(CalendarContract.Events.DESCRIPTION, "Precio medio PVPC ${Fmt.price(w.avgPriceEurKwh)}")
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, w.start.toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toEpochMilli())
                try { context.startActivity(intent) } catch (_: Exception) { }
            }) { Text("Añadir al calendario") }
        }
    }
}

@Composable
private fun ShiftCard(ui: PricesUi) {
    SectionCard("Recomendación de desplazamiento") {
        val s = ui.shift
        if (s == null) {
            Text("Necesito tu historial de consumo (credenciales de Datadis) y los precios de las próximas horas.")
        } else {
            Text(
                "Tu mayor consumo suele ser a las ${Fmt.hourOfDay(s.peakHour)}; hoy la hora más barata es a las " +
                    "${Fmt.hourOfDay(s.cheapHour)}. Ahorrarías ${Fmt.price(s.savingEurPerKwh)} por cada kWh que muevas.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
