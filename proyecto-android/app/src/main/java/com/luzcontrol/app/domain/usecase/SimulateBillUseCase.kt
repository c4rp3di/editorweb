package com.luzcontrol.app.domain.usecase

import com.luzcontrol.app.domain.model.*
import java.time.YearMonth
import javax.inject.Inject

/**
 * Estimación de factura 2.0TD PVPC:
 * energía (kWh × PVPC horario) + potencia + alquiler de contador + bono social
 * → impuesto eléctrico → IVA. Son estimaciones, no sustituyen la factura oficial.
 */
class SimulateBillUseCase @Inject constructor(private val cost: CalculateCostUseCase) {

    operator fun invoke(
        month: YearMonth,
        consumption: List<ConsumptionPoint>,
        prices: List<PricePoint>,
        contract: Contract?,
        params: BillingParams
    ): BillSimulation? {
        if (consumption.isEmpty()) return null
        val zone = TariffCalendar.ZONE
        val daysCovered = consumption.map { it.start.atZone(zone).toLocalDate() }.distinct().size
        val daysInMonth = month.lengthOfMonth()
        val partial = daysCovered < daysInMonth
        val billedDays = if (partial) daysCovered else daysInMonth

        val c = cost(consumption, prices)
        val totalKwh = c.pricedKwh + c.unpricedKwh
        // kWh sin precio: se valoran al precio medio de las horas que sí lo tienen
        val energyCost = c.totalEur + c.unpricedKwh * (c.avgPriceEurKwh ?: 0.0)
        val coverage = if (totalKwh > 0) c.pricedKwh / totalKwh else 0.0

        val power = if (contract != null)
            (contract.powerP1Kw * params.powerP1EurKwDay + contract.powerP2Kw * params.powerP2EurKwDay) * billedDays
        else 0.0
        val meter = params.meterRentEurDay * billedDays
        val bono = params.socialBonusEurDay * billedDays
        val taxBase = energyCost + power + bono
        val tax = taxBase * params.electricityTaxPct / 100.0
        val subtotal = taxBase + tax + meter
        val iva = subtotal * params.ivaPct / 100.0
        val total = subtotal + iva

        val projected = if (partial && daysCovered > 0) {
            val e = energyCost * daysInMonth / daysCovered
            val p = if (contract != null)
                (contract.powerP1Kw * params.powerP1EurKwDay + contract.powerP2Kw * params.powerP2EurKwDay) * daysInMonth
            else 0.0
            val b = params.socialBonusEurDay * daysInMonth
            val t = (e + p + b) * params.electricityTaxPct / 100.0
            val sub = e + p + b + t + params.meterRentEurDay * daysInMonth
            sub * (1 + params.ivaPct / 100.0)
        } else null

        return BillSimulation(
            month = month,
            daysCovered = daysCovered,
            daysInMonth = daysInMonth,
            partial = partial,
            energyKwh = totalKwh,
            energyCostEur = energyCost,
            powerCostEur = power,
            meterRentEur = meter,
            socialBonusEur = bono,
            electricityTaxEur = tax,
            subtotalEur = subtotal,
            ivaEur = iva,
            totalEur = total,
            projectedTotalEur = projected,
            priceCoverage = coverage,
            missingContract = contract == null
        )
    }
}
