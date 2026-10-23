package com.luzcontrol.app.domain.usecase

import com.luzcontrol.app.domain.model.Anomaly
import com.luzcontrol.app.domain.model.ConsumptionPoint
import com.luzcontrol.app.domain.model.TariffCalendar
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Detecta picos de consumo comparando cada hora con la media de la misma hora
 * (mismo día de la semana) de las 4 semanas anteriores.
 */
class DetectAnomaliesUseCase @Inject constructor() {

    operator fun invoke(
        points: List<ConsumptionPoint>,
        from: Instant,
        to: Instant,
        minSamples: Int = 2,
        ratio: Double = 1.8,
        minExcessKwh: Double = 0.4,
        limit: Int = 5
    ): List<Anomaly> {
        if (points.isEmpty()) return emptyList()
        val hourly = HashMap<Instant, Double>()
        for (p in points) {
            val h = p.start.truncatedTo(ChronoUnit.HOURS)
            hourly[h] = (hourly[h] ?: 0.0) + p.kwh
        }
        val found = ArrayList<Anomaly>()
        for ((hour, kwh) in hourly) {
            if (hour < from || hour >= to) continue
            val z = hour.atZone(TariffCalendar.ZONE)
            val samples = (1..4).mapNotNull { k -> hourly[z.minusWeeks(k.toLong()).toInstant()] }
            if (samples.size < minSamples) continue
            val mean = samples.average()
            if (mean <= 0.0) continue
            if (kwh > mean * ratio && kwh - mean >= minExcessKwh) {
                found += Anomaly(hour, kwh, mean, (kwh / mean - 1.0) * 100.0)
            }
        }
        // Un solo pico por día (el mayor) y los más relevantes primero
        return found
            .groupBy { it.start.atZone(TariffCalendar.ZONE).toLocalDate() }
            .map { (_, list) -> list.maxByOrNull { it.kwh - it.baselineKwh }!! }
            .sortedByDescending { it.kwh - it.baselineKwh }
            .take(limit)
    }
}
