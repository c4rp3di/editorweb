package com.luzcontrol.app.domain.usecase

import com.luzcontrol.app.domain.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Estima el consumo base ("fantasma") a partir de la curva nocturna (02:00–06:00). */
class EstimateStandbyUseCase @Inject constructor() {

    operator fun invoke(points: List<ConsumptionPoint>, prices: List<PricePoint>): StandbyReport? {
        if (points.isEmpty()) return null
        val hourly = HashMap<Instant, Double>()
        for (p in points) {
            val h = p.start.truncatedTo(ChronoUnit.HOURS)
            hourly[h] = (hourly[h] ?: 0.0) + p.kwh
        }
        val byDay = HashMap<LocalDate, MutableList<Double>>()
        val allHours = ArrayList<Double>()
        for ((h, kwh) in hourly) {
            val z = h.atZone(TariffCalendar.ZONE)
            allHours += kwh
            if (z.hour in 2..5) byDay.getOrPut(z.toLocalDate()) { ArrayList() }.add(kwh)
        }
        val nightMedians = byDay.values.filter { it.size >= 3 }.map { median(it) }
        if (nightMedians.size < 5) return null
        val baseKwhPerHour = median(nightMedians)
        val baseWatts = baseKwhPerHour * 1000.0
        val avgWatts = allHours.average() * 1000.0
        val monthlyKwh = baseKwhPerHour * 24.0 * 30.0
        val avgPrice = if (prices.isNotEmpty()) prices.map { it.pvpcEurKwh }.average() else null
        return StandbyReport(
            baseWatts = baseWatts,
            monthlyKwh = monthlyKwh,
            monthlyCostEur = avgPrice?.let { it * monthlyKwh },
            high = baseWatts >= 200.0 || (avgWatts > 0 && baseWatts > 0.35 * avgWatts && baseWatts >= 80.0),
            sampleDays = nightMedians.size
        )
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}

/** Sugiere mover el consumo de tu hora habitual de mayor uso a la hora más barata. */
class RecommendShiftUseCase @Inject constructor() {

    /**
     * @param history consumo histórico reciente (para conocer tu hora punta de uso)
     * @param upcomingPrices precios de las próximas ~24 h
     */
    operator fun invoke(history: List<ConsumptionPoint>, upcomingPrices: List<PricePoint>): ShiftRecommendation? {
        if (history.isEmpty() || upcomingPrices.size < 6) return null
        val sumByHour = DoubleArray(24)
        val countByHour = IntArray(24)
        val perHour = HashMap<Instant, Double>()
        for (p in history) {
            val h = p.start.truncatedTo(ChronoUnit.HOURS)
            perHour[h] = (perHour[h] ?: 0.0) + p.kwh
        }
        for ((h, kwh) in perHour) {
            val hour = h.atZone(TariffCalendar.ZONE).hour
            sumByHour[hour] += kwh
            countByHour[hour] += 1
        }
        var peakHour = -1
        var peakMean = 0.0
        for (h in 0 until 24) {
            if (countByHour[h] == 0) continue
            val mean = sumByHour[h] / countByHour[h]
            if (mean > peakMean) { peakMean = mean; peakHour = h }
        }
        if (peakHour < 0) return null
        val priceAtPeak = upcomingPrices.firstOrNull { it.start.atZone(TariffCalendar.ZONE).hour == peakHour }
            ?: return null
        val cheapest = upcomingPrices.minByOrNull { it.pvpcEurKwh } ?: return null
        val saving = priceAtPeak.pvpcEurKwh - cheapest.pvpcEurKwh
        if (saving <= 0.0) return null
        return ShiftRecommendation(
            peakHour = peakHour,
            peakHourPriceEurKwh = priceAtPeak.pvpcEurKwh,
            cheapHour = cheapest.start.atZone(TariffCalendar.ZONE).hour,
            cheapHourPriceEurKwh = cheapest.pvpcEurKwh,
            savingEurPerKwh = saving
        )
    }
}

/** Mejor ventana contigua de N horas (precio medio mínimo). */
class FindCheapestWindowUseCase @Inject constructor() {

    operator fun invoke(prices: List<PricePoint>, hours: Int): CheapWindow? {
        val sorted = prices.sortedBy { it.start }
        if (hours < 1 || sorted.size < hours) return null
        var best: CheapWindow? = null
        for (i in 0..sorted.size - hours) {
            val window = sorted.subList(i, i + hours)
            // Las horas deben ser contiguas
            val contiguous = (1 until window.size).all {
                ChronoUnit.HOURS.between(window[it - 1].start, window[it].start) == 1L
            }
            if (!contiguous) continue
            val avg = window.map { it.pvpcEurKwh }.average()
            if (best == null || avg < best.avgPriceEurKwh) {
                best = CheapWindow(window.first().start, hours, avg)
            }
        }
        return best
    }
}
