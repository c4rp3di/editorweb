package com.luzcontrol.app.domain.usecase

import com.luzcontrol.app.domain.model.*
import java.time.YearMonth
import javax.inject.Inject
import kotlin.math.ceil

/** Compara la potencia máxima demandada (maxímetro) con la potencia contratada. */
class AnalyzePowerUseCase @Inject constructor() {

    operator fun invoke(maxPower: List<MaxPowerPoint>, contract: Contract?): PowerAnalysis {
        val months = maxPower.groupBy { YearMonth.from(it.date) }
            .map { (ym, list) ->
                // P1 contratada cubre punta y llano; P2 contratada cubre valle
                val p1 = list.filter { it.period != TariffPeriod.P3 }.maxOfOrNull { it.kw } ?: 0.0
                val p2 = list.filter { it.period == TariffPeriod.P3 }.maxOfOrNull { it.kw } ?: 0.0
                MonthlyPower(ym, p1, p2)
            }
            .sortedBy { it.month }

        val c1 = contract?.powerP1Kw?.takeIf { it > 0 }
        val c2 = contract?.powerP2Kw?.takeIf { it > 0 }
        if (months.isEmpty() || c1 == null) {
            return PowerAnalysis(months, c1, c2, PowerRecommendation.NO_DATA, null)
        }
        val peak = months.maxOf { it.maxKwP1 }
        val rec = when {
            peak >= c1 * 0.98 -> PowerRecommendation.UNDERSIZED
            months.size >= 6 && peak < c1 * 0.6 -> PowerRecommendation.OVERSIZED
            else -> PowerRecommendation.OK
        }
        val suggested = if (rec == PowerRecommendation.OVERSIZED)
            ceil(peak * 1.15 * 10.0) / 10.0 else null
        return PowerAnalysis(months, c1, c2, rec, suggested)
    }
}
