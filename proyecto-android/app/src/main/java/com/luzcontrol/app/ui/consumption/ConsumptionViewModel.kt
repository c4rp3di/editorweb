package com.luzcontrol.app.ui.consumption

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.Color
import com.luzcontrol.app.domain.model.*
import com.luzcontrol.app.domain.repository.EnergyRepository
import com.luzcontrol.app.domain.usecase.*
import com.luzcontrol.app.ui.components.BarEntry
import com.luzcontrol.app.ui.components.Fmt
import com.luzcontrol.app.ui.components.UiState
import com.luzcontrol.app.ui.theme.EnergyColors
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
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

enum class RangePreset(val label: String) {
    LAST_7("7 días"), LAST_30("30 días"), MONTH("Mes actual"), YEAR("Año actual"), CUSTOM("Personalizado")
}

data class RangeSelection(
    val preset: RangePreset = RangePreset.LAST_30,
    val customFrom: LocalDate? = null,
    val customTo: LocalDate? = null
) {
    fun resolve(today: LocalDate): Pair<LocalDate, LocalDate> = when (preset) {
        RangePreset.LAST_7 -> today.minusDays(6) to today
        RangePreset.LAST_30 -> today.minusDays(29) to today
        RangePreset.MONTH -> today.withDayOfMonth(1) to today
        RangePreset.YEAR -> today.withDayOfYear(1) to today
        RangePreset.CUSTOM -> {
            val a = customFrom ?: today.minusDays(29)
            val b = customTo ?: today
            if (a.isAfter(b)) b to a else a to b
        }
    }
}

enum class Granularity(val label: String) { HOURLY("por horas"), DAILY("por días"), MONTHLY("por meses") }

data class ConsumptionUi(
    val rangeLabel: String,
    val granularity: Granularity,
    val bars: List<BarEntry>,
    val priceLine: List<Double?>,
    val totalKwh: Double,
    val totalCostEur: Double?,
    val avgPrice: Double?,
    val anomalies: List<Anomaly>,
    val power: PowerAnalysis,
    val standby: StandbyReport?,
    val surplusKwh: Double
)

@HiltViewModel
class ConsumptionViewModel @Inject constructor(
    private val observeActiveCups: ObserveActiveCupsUseCase,
    private val energy: EnergyRepository,
    private val calculateCost: CalculateCostUseCase,
    private val detectAnomalies: DetectAnomaliesUseCase,
    private val analyzePower: AnalyzePowerUseCase,
    private val estimateStandby: EstimateStandbyUseCase
) : ViewModel() {

    private val zone = TariffCalendar.ZONE
    private val _range = MutableStateFlow(RangeSelection())
    val range: StateFlow<RangeSelection> = _range

    fun selectPreset(p: RangePreset) { _range.value = _range.value.copy(preset = p) }
    fun setCustom(from: LocalDate, to: LocalDate) {
        _range.value = RangeSelection(RangePreset.CUSTOM, from, to)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<ConsumptionUi>> =
        combine(observeActiveCups(), _range) { cups, r -> cups to r }
            .flatMapLatest { (cups, r) ->
                if (cups == null) flowOf<UiState<ConsumptionUi>>(
                    UiState.Empty("Aún no hay datos de consumo. Configura tus credenciales de Datadis en Ajustes.")
                )
                else build(cups, r)
            }
            .catch { emit(UiState.Error("No se pudieron leer los datos guardados.")) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    private fun build(cups: String, r: RangeSelection): Flow<UiState<ConsumptionUi>> {
        val today = LocalDate.now(zone)
        val (fromDate, toDate) = r.resolve(today)
        val from = fromDate.atStartOfDay(zone).toInstant()
        val to = toDate.plusDays(1).atStartOfDay(zone).toInstant()
        val extFrom = from.minus(28, ChronoUnit.DAYS)
        val priceFrom = from.minus(30, ChronoUnit.DAYS)
        return combine(
            energy.observeConsumption(cups, extFrom, to),
            energy.observePrices(priceFrom, to),
            energy.observeContract(cups),
            energy.observeMaxPower(cups, today.minusMonths(12).withDayOfMonth(1), today)
        ) { extConsumption, prices, contract, maxPower ->
            val inRange = extConsumption.filter { it.start >= from && it.start < to }
            if (inRange.isEmpty()) {
                UiState.Empty("No hay lecturas de consumo en el periodo seleccionado.")
            } else {
                val rangePrices = prices.filter { it.start >= from && it.start < to }
                val cost = calculateCost(inRange, rangePrices)
                val (bars, line, gran) = aggregate(inRange, rangePrices, fromDate, toDate)
                val anomalies = detectAnomalies(extConsumption, from, to)
                val standby = estimateStandby(
                    extConsumption.filter { it.start >= to.minus(30, ChronoUnit.DAYS) && it.start < to },
                    rangePrices
                )
                val ui = ConsumptionUi(
                    rangeLabel = Fmt.date(fromDate) + " – " + Fmt.date(toDate),
                    granularity = gran,
                    bars = bars,
                    priceLine = line,
                    totalKwh = inRange.sumOf { it.kwh },
                    totalCostEur = if (cost.pricedKwh > 0) cost.totalEur else null,
                    avgPrice = cost.avgPriceEurKwh,
                    anomalies = anomalies,
                    power = analyzePower(maxPower, contract),
                    standby = standby,
                    surplusKwh = inRange.sumOf { it.surplusKwh }
                )
                UiState.Success(ui)
            }
        }
    }

    private data class Aggregated(val bars: List<BarEntry>, val line: List<Double?>, val gran: Granularity)

    private fun aggregate(
        points: List<ConsumptionPoint>,
        prices: List<PricePoint>,
        fromDate: LocalDate,
        toDate: LocalDate
    ): Aggregated {
        val days = ChronoUnit.DAYS.between(fromDate, toDate) + 1
        val priceByHour = prices.associateBy { it.start.truncatedTo(ChronoUnit.HOURS) }
        return when {
            days <= 3 -> {
                val hourly = points.groupBy { it.start.truncatedTo(ChronoUnit.HOURS) }
                    .map { (h, list) -> h to list.sumOf { it.kwh } }
                    .sortedBy { it.first }
                val fmt = DateTimeFormatter.ofPattern("dd/MM HH'h'")
                Aggregated(
                    bars = hourly.map { (h, kwh) ->
                        val period = TariffCalendar.periodAt(h)
                        BarEntry(h.atZone(zone).format(fmt), kwh, periodColor(period), period.label)
                    },
                    line = hourly.map { (h, _) -> priceByHour[h]?.pvpcEurKwh },
                    gran = Granularity.HOURLY
                )
            }
            days <= 92 -> {
                val daily = points.groupBy { it.start.atZone(zone).toLocalDate() }
                    .map { (d, list) -> d to list.sumOf { it.kwh } }
                    .sortedBy { it.first }
                val pricesByDay = prices.groupBy { it.start.atZone(zone).toLocalDate() }
                    .mapValues { (_, l) -> l.map { it.pvpcEurKwh }.average() }
                Aggregated(
                    bars = daily.map { (d, kwh) -> BarEntry(Fmt.shortDate(d), kwh) },
                    line = daily.map { (d, _) -> pricesByDay[d] },
                    gran = Granularity.DAILY
                )
            }
            else -> {
                val monthly = points.groupBy { YearMonth.from(it.start.atZone(zone)) }
                    .map { (m, list) -> m to list.sumOf { it.kwh } }
                    .sortedBy { it.first }
                val pricesByMonth = prices.groupBy { YearMonth.from(it.start.atZone(zone)) }
                    .mapValues { (_, l) -> l.map { it.pvpcEurKwh }.average() }
                val fmt = DateTimeFormatter.ofPattern("MM/yy")
                Aggregated(
                    bars = monthly.map { (m, kwh) -> BarEntry(m.format(fmt), kwh) },
                    line = monthly.map { (m, _) -> pricesByMonth[m] },
                    gran = Granularity.MONTHLY
                )
            }
        }
    }

    private fun periodColor(p: TariffPeriod): Color = when (p) {
        TariffPeriod.P1 -> EnergyColors.Punta
        TariffPeriod.P2 -> EnergyColors.Llano
        TariffPeriod.P3 -> EnergyColors.Valle
    }
}
