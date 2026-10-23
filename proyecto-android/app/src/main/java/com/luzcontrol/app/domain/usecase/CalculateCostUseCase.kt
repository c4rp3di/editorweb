package com.luzcontrol.app.domain.usecase

import com.luzcontrol.app.domain.model.ConsumptionPoint
import com.luzcontrol.app.domain.model.CostResult
import com.luzcontrol.app.domain.model.PricePoint
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Coste de energía = Σ kWh de cada hora × precio PVPC de esa hora. */
class CalculateCostUseCase @Inject constructor() {

    operator fun invoke(consumption: List<ConsumptionPoint>, prices: List<PricePoint>): CostResult {
        val byHour = prices.associateBy { it.start.truncatedTo(ChronoUnit.HOURS) }
        var total = 0.0
        var priced = 0.0
        var unpriced = 0.0
        for (c in consumption) {
            val price = byHour[c.start.truncatedTo(ChronoUnit.HOURS)]
            if (price != null) {
                total += c.kwh * price.pvpcEurKwh
                priced += c.kwh
            } else {
                unpriced += c.kwh
            }
        }
        return CostResult(
            totalEur = total,
            pricedKwh = priced,
            unpricedKwh = unpriced,
            avgPriceEurKwh = if (priced > 0.0) total / priced else null
        )
    }

    /** Coste hora a hora (útil para gráficas). */
    fun hourly(consumption: List<ConsumptionPoint>, prices: List<PricePoint>): Map<Instant, Double> {
        val byHour = prices.associateBy { it.start.truncatedTo(ChronoUnit.HOURS) }
        val out = LinkedHashMap<Instant, Double>()
        for (c in consumption) {
            val h = c.start.truncatedTo(ChronoUnit.HOURS)
            val p = byHour[h] ?: continue
            out[h] = (out[h] ?: 0.0) + c.kwh * p.pvpcEurKwh
        }
        return out
    }
}
