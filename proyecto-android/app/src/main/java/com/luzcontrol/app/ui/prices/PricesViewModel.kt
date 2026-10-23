package com.luzcontrol.app.ui.prices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.luzcontrol.app.domain.model.*
import com.luzcontrol.app.domain.repository.EnergyRepository
import com.luzcontrol.app.domain.usecase.FindCheapestWindowUseCase
import com.luzcontrol.app.domain.usecase.ObserveActiveCupsUseCase
import com.luzcontrol.app.domain.usecase.RecommendShiftUseCase
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

enum class PriceLevel(val label: String) { CHEAP("Barata"), MEDIUM("Media"), EXPENSIVE("Cara") }

data class HourPrice(val start: Instant, val priceEurKwh: Double, val level: PriceLevel)

data class PricesUi(
    val now: HourPrice?,
    val today: List<HourPrice>,
    val tomorrow: List<HourPrice>,
    val cheapestToday: HourPrice?,
    val dearestToday: HourPrice?,
    val futurePrices: List<HourPrice>,
    val historyLabels: List<String>,
    val historyValues: List<Double>,
    val shift: ShiftRecommendation?,
    val window: CheapWindow?,
    val windowHours: Int
)

@HiltViewModel
class PricesViewModel @Inject constructor(
    private val energy: EnergyRepository,
    private val observeActiveCups: ObserveActiveCupsUseCase,
    private val recommendShift: RecommendShiftUseCase,
    private val findWindow: FindCheapestWindowUseCase
) : ViewModel() {

    private val zone = TariffCalendar.ZONE
    private val _windowHours = MutableStateFlow(2)
    val windowHours: StateFlow<Int> = _windowHours
    fun setWindowHours(h: Int) { _windowHours.value = h.coerceIn(1, 6) }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<PricesUi>> = observeActiveCups()
        .flatMapLatest { cups -> build(cups) }
        .catch { emit(UiState.Error("No se pudieron leer los precios guardados.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    private fun build(cups: String?): Flow<UiState<PricesUi>> {
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val from = today.minusDays(30).atStartOfDay(zone).toInstant()
        val to = today.plusDays(2).atStartOfDay(zone).toInstant()
        val history: Flow<List<ConsumptionPoint>> = if (cups == null) flowOf(emptyList())
        else energy.observeConsumption(cups, today.minusDays(30).atStartOfDay(zone).toInstant(), to)
        return combine(energy.observePrices(from, to), history, _windowHours) { prices, consumption, wh ->
            if (prices.isEmpty()) {
                UiState.Empty("Aún no hay precios. Pulsa «Sincronizar» (no necesitas credenciales para ver los precios).")
            } else {
                val ui = compose(prices, consumption, wh, now, today)
                val last = prices.maxOf { it.start }
                if (Duration.between(last, now).toHours() > 24) UiState.Stale(ui) else UiState.Success(ui)
            }
        }
    }

    private fun levels(day: List<PricePoint>): List<HourPrice> {
        if (day.isEmpty()) return emptyList()
        val sorted = day.map { it.pvpcEurKwh }.sorted()
        val p33 = sorted[(sorted.size * 0.33).toInt().coerceIn(0, sorted.lastIndex)]
        val p66 = sorted[(sorted.size * 0.66).toInt().coerceIn(0, sorted.lastIndex)]
        return day.map {
            HourPrice(
                it.start, it.pvpcEurKwh,
                when {
                    it.pvpcEurKwh <= p33 -> PriceLevel.CHEAP
                    it.pvpcEurKwh >= p66 -> PriceLevel.EXPENSIVE
                    else -> PriceLevel.MEDIUM
                }
            )
        }
    }

    private fun compose(
        prices: List<PricePoint>,
        consumption: List<ConsumptionPoint>,
        windowHours: Int,
        now: Instant,
        today: LocalDate
    ): PricesUi {
        val byDay = prices.groupBy { it.start.atZone(zone).toLocalDate() }
        val todayList = levels(byDay[today].orEmpty())
        val tomorrowList = levels(byDay[today.plusDays(1)].orEmpty())
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        val nowPrice = todayList.firstOrNull { it.start == hour }
        val future = (todayList + tomorrowList).filter { it.start >= hour }
        val futurePoints = prices.filter { it.start >= hour && it.start < hour.plus(24, ChronoUnit.HOURS) }

        val hist = byDay.filterKeys { it < today.plusDays(1) }.toSortedMap()
        val labels = hist.keys.map { "%02d/%02d".format(it.dayOfMonth, it.monthValue) }
        val values = hist.values.map { l -> l.map { it.pvpcEurKwh }.average() }

        return PricesUi(
            now = nowPrice,
            today = todayList,
            tomorrow = tomorrowList,
            cheapestToday = todayList.minByOrNull { it.priceEurKwh },
            dearestToday = todayList.maxByOrNull { it.priceEurKwh },
            futurePrices = future,
            historyLabels = labels,
            historyValues = values,
            shift = recommendShift(consumption, futurePoints),
            window = findWindow(prices.filter { it.start >= hour }, windowHours),
            windowHours = windowHours
        )
    }
}
