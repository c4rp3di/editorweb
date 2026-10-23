package com.luzcontrol.app.ui.bills

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luzcontrol.app.domain.model.BillSimulation
import com.luzcontrol.app.ui.components.Fmt
import com.luzcontrol.app.ui.components.NoticeBanner
import com.luzcontrol.app.ui.components.SectionCard
import com.luzcontrol.app.ui.components.StateHost
import com.luzcontrol.app.ui.components.StatRow
import java.time.format.DateTimeFormatter

private val monthFmt = DateTimeFormatter.ofPattern("MM/yyyy")

@Composable
fun BillsScreen(onOpenSettings: () -> Unit, viewModel: BillsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    StateHost(state = state, onRetry = {}, emptyAction = "Ir a Ajustes", onEmptyAction = onOpenSettings) { ui ->
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.history) { s ->
                        FilterChip(
                            selected = s.month == ui.selected.month,
                            onClick = { viewModel.selectMonth(s.month) },
                            label = { Text(s.month.format(monthFmt)) }
                        )
                    }
                }
            }
            item { SimulationCard(ui.selected) }
            item {
                NoticeBanner(
                    "Los importes son estimaciones basadas en tus datos de consumo y precios PVPC; " +
                        "no sustituyen a la factura oficial de tu comercializadora."
                )
            }
            item {
                SectionCard("Historial de facturas simuladas") {
                    ui.history.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.selectMonth(s.month) }.padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(s.month.format(monthFmt) + if (s.partial) " (parcial)" else "")
                            Text("${Fmt.kwh1(s.energyKwh)} · ${Fmt.eur(s.totalEur)}")
                        }
                    }
                }
            }
            item {
                SectionCard("Comparador de tarifas", subtitle = "Comprueba si hay una tarifa más barata") {
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://comparador.cnmc.gob.es/")))
                        } catch (_: Exception) { }
                    }) { Text("Abrir comparador de la CNMC") }
                }
            }
        }
    }
}

@Composable
private fun SimulationCard(s: BillSimulation) {
    SectionCard(
        "Factura de ${s.month.format(monthFmt)}",
        subtitle = if (s.partial) "Mes en curso: ${s.daysCovered} de ${s.daysInMonth} días con datos" else "${s.daysInMonth} días"
    ) {
        Text(Fmt.eur(s.totalEur), style = MaterialTheme.typography.headlineMedium)
        if (s.partial && s.projectedTotalEur != null) {
            Text("Proyección del mes completo: ${Fmt.eur(s.projectedTotalEur)}", style = MaterialTheme.typography.bodyLarge)
        }
        HorizontalDivider()
        StatRow("Energía (${Fmt.kwh1(s.energyKwh)})", Fmt.eur(s.energyCostEur))
        StatRow("Potencia contratada", Fmt.eur(s.powerCostEur))
        StatRow("Bono social", Fmt.eur(s.socialBonusEur))
        StatRow("Impuesto eléctrico", Fmt.eur(s.electricityTaxEur))
        StatRow("Alquiler de contador", Fmt.eur(s.meterRentEur))
        StatRow("IVA", Fmt.eur(s.ivaEur))
        HorizontalDivider()
        StatRow("Total estimado", Fmt.eur(s.totalEur), emphasized = true)
        if (s.missingContract) {
            Text(
                "Sin datos de contrato: el término de potencia no se ha incluido.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
            )
        }
        if (s.priceCoverage < 0.98) {
            Text(
                "Solo el ${Fmt.pct(s.priceCoverage * 100)} del consumo tiene precio horario; el resto se valora al precio medio.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
