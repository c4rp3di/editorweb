package com.luzcontrol.app.ui.bills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.luzcontrol.app.domain.model.BillSimulation
import com.luzcontrol.app.domain.model.TariffCalendar
import com.luzcontrol.app.domain.repository.EnergyRepository
import com.luzcontrol.app.domain.repository.SettingsRepository
import com.luzcontrol.app.domain.usecase.ObserveActiveCupsUseCase
import com.luzcontrol.app.domain.usecase.SimulateBillUseCase
import com.luzcontrol.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class BillsUi(
    val history: List<BillSimulation>,
    val selected: BillSimulation
)

@HiltViewModel
class BillsViewModel @Inject constructor(
    private val observeActiveCups: ObserveActiveCupsUseCase,
    private val energy: EnergyRepository,
    private val settings: SettingsRepository,
    private val simulate: SimulateBillUseCase
) : ViewModel() {

    private val zone = TariffCalendar.ZONE
    private val selectedMonth = MutableStateFlow<YearMonth?>(null)
    fun selectMonth(m: YearMonth) { selectedMonth.value = m }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<BillsUi>> = observeActiveCups()
        .flatMapLatest { cups -> build(cups) }
        .catch { emit(UiState.Error("No se pudo calcular la simulación.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    private fun build(cups: String?): Flow<UiState<BillsUi>> {
        if (cups == null) {
            return flowOf(UiState.Empty("Para simular facturas necesito tu consumo: configura tus credenciales de Datadis en Ajustes."))
        }
        val today = LocalDate.now(zone)
        val first = today.minusMonths(12).withDayOfMonth(1).atStartOfDay(zone).toInstant()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant()
        return combine(
            energy.observeConsumption(cups, first, to),
            energy.observePrices(first, to),
            energy.observeContract(cups),
            settings.settings,
            selectedMonth
        ) { consumption, prices, contract, userSettings, selected ->
            if (consumption.isEmpty()) {
                UiState.Empty("Aún no hay consumo guardado para simular una factura.")
            } else {
                val byMonth = consumption.groupBy { YearMonth.from(it.start.atZone(zone)) }
                val sims = byMonth.mapNotNull { (m, points) ->
                    simulate(m, points, prices, contract, userSettings.billing)
                }.sortedByDescending { it.month }
                val chosen = sims.firstOrNull { it.month == selected } ?: sims.first()
                UiState.Success(BillsUi(sims, chosen))
            }
        }
    }
}
