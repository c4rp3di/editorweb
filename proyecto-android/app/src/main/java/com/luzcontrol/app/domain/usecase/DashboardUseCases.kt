package com.luzcontrol.app.domain.usecase

import com.luzcontrol.app.domain.model.*
import com.luzcontrol.app.domain.repository.EnergyRepository
import com.luzcontrol.app.domain.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** CUPS activo: el elegido por el usuario o, si no hay, el primero disponible. */
class ObserveActiveCupsUseCase @Inject constructor(
    private val energy: EnergyRepository,
    private val settings: SettingsRepository
) {
    operator fun invoke(): Flow<String?> =
        combine(settings.settings, energy.observeSupplies()) { s, supplies ->
            s.activeCups?.takeIf { c -> supplies.any { it.cups == c } } ?: supplies.firstOrNull()?.cups
        }.distinctUntilChanged()
}

class GetDashboardUseCase @Inject constructor(
    private val energy: EnergyRepository,
    private val cost: CalculateCostUseCase
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(cups: String, now: Instant = Instant.now()): Flow<DashboardData> {
        val zone = TariffCalendar.ZONE
        val today = now.atZone(zone).toLocalDate()
        val from = today.minusDays(45).atStartOfDay(zone).toInstant()
        val to = today.plusDays(2).atStartOfDay(zone).toInstant()
        val monthStart = today.withDayOfMonth(1)
        return combine(
            energy.observeConsumption(cups, from, to),
            energy.observePrices(from, to),
            energy.observeContract(cups),
            energy.observeMaxPower(cups, monthStart, today),
            energy.observeLastSync()
        ) { consumption, prices, contract, maxPower, lastSync ->
            val lastPoint = consumption.maxByOrNull { it.start }
            val dataUntil: LocalDate? = lastPoint?.start?.atZone(zone)?.toLocalDate()
            val refDay = dataUntil
            val dayPoints = if (refDay == null) emptyList() else consumption.filter {
                it.start.atZone(zone).toLocalDate() == refDay
            }
            val dayKwh = if (refDay != null) dayPoints.sumOf { it.kwh } else null
            val dayCost = if (dayPoints.isNotEmpty()) cost(dayPoints, prices) else null
            val periodKwh = TariffPeriod.values().associateWith { p ->
                dayPoints.filter { TariffCalendar.periodAt(it.start) == p }.sumOf { it.kwh }
            }
            val monthKwh = consumption.filter {
                val d = it.start.atZone(zone).toLocalDate()
                d.year == today.year && d.month == today.month
            }.sumOf { it.kwh }
            val maxKw = maxPower.maxOfOrNull { it.kw }
            val stale = lastPoint == null ||
                Duration.between(lastPoint.start, now).toHours() > 48 + 24
            DashboardData(
                lastSync = lastSync?.takeIf { it.success }?.timestamp ?: lastSync?.timestamp,
                lastSyncOk = lastSync?.success,
                lastSyncMessage = lastSync?.message,
                dataUntil = dataUntil,
                referenceDay = refDay,
                dayKwh = dayKwh,
                dayCostEur = dayCost?.let { it.totalEur },
                dayAvgPrice = dayCost?.avgPriceEurKwh,
                dayPartialPrices = dayCost != null && dayCost.unpricedKwh > 0.0,
                periodKwh = periodKwh,
                monthKwh = monthKwh,
                maxPowerMonthKw = maxKw,
                contractedKw = contract?.powerP1Kw?.takeIf { it > 0 },
                stale = stale
            )
        }
    }
}
