package com.luzcontrol.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Periodos tarifarios 2.0TD: P1 = Punta, P2 = Llano, P3 = Valle. */
enum class TariffPeriod(val label: String, val code: Int) {
    P1("Punta", 1),
    P2("Llano", 2),
    P3("Valle", 3);

    companion object {
        fun fromCode(code: Int): TariffPeriod = values().firstOrNull { it.code == code } ?: P3
    }
}

data class Supply(
    val cups: String,
    val distributorCode: String,
    val distributor: String,
    val address: String,
    val municipality: String,
    val province: String,
    val pointType: Int,
    val alias: String?,
    val isActive: Boolean
) {
    val displayName: String
        get() = alias?.takeIf { it.isNotBlank() } ?: address.ifBlank { cups }
}

data class Contract(
    val cups: String,
    val powerP1Kw: Double,
    val powerP2Kw: Double,
    val tariff: String,
    val marketer: String,
    val startDate: String?,
    val timeDiscrimination: String?
)

/** Lectura de consumo. [start] es el inicio del intervalo (hora o cuarto de hora). */
data class ConsumptionPoint(
    val start: Instant,
    val kwh: Double,
    val surplusKwh: Double,
    val real: Boolean
)

/** Precio horario en €/kWh (PVPC y, si está disponible, mercado spot). */
data class PricePoint(
    val start: Instant,
    val pvpcEurKwh: Double,
    val spotEurKwh: Double?
)

data class MaxPowerPoint(
    val date: LocalDate,
    val time: String,
    val kw: Double,
    val period: TariffPeriod
)

data class SyncLog(
    val id: Long,
    val timestamp: Instant,
    val source: String,
    val success: Boolean,
    val message: String?
)

enum class SyncMode { DAILY, ON_DEMAND }

data class SyncResult(
    val success: Boolean,
    val partial: Boolean,
    val errors: List<AppError>,
    val message: String
)

/** Parámetros editables para estimar la factura. Valores por defecto orientativos (2.0TD, península). */
data class BillingParams(
    val powerP1EurKwDay: Double = 0.104229,
    val powerP2EurKwDay: Double = 0.003902,
    val meterRentEurDay: Double = 0.02663,
    val socialBonusEurDay: Double = 0.012742,
    val electricityTaxPct: Double = 5.11269632,
    val ivaPct: Double = 21.0
)

data class UserSettings(
    val activeCups: String? = null,
    val dailySync: Boolean = true,
    val priceNotifications: Boolean = false,
    val requireBiometric: Boolean = false,
    val datadisApiV2: Boolean = false,
    val ufdEnabled: Boolean = false,
    val billing: BillingParams = BillingParams()
)

data class DatadisCredentials(val username: String, val password: String)

// ---------- Resultados de casos de uso ----------

data class CostResult(
    val totalEur: Double,
    val pricedKwh: Double,
    val unpricedKwh: Double,
    val avgPriceEurKwh: Double?
)

data class DashboardData(
    val lastSync: Instant?,
    val lastSyncOk: Boolean?,
    val lastSyncMessage: String?,
    val dataUntil: LocalDate?,
    val referenceDay: LocalDate?,
    val dayKwh: Double?,
    val dayCostEur: Double?,
    val dayAvgPrice: Double?,
    val dayPartialPrices: Boolean,
    val periodKwh: Map<TariffPeriod, Double>,
    val monthKwh: Double,
    val maxPowerMonthKw: Double?,
    val contractedKw: Double?,
    val stale: Boolean
)

data class Anomaly(
    val start: Instant,
    val kwh: Double,
    val baselineKwh: Double,
    val excessPct: Double
)

data class StandbyReport(
    val baseWatts: Double,
    val monthlyKwh: Double,
    val monthlyCostEur: Double?,
    val high: Boolean,
    val sampleDays: Int
)

data class ShiftRecommendation(
    val peakHour: Int,
    val peakHourPriceEurKwh: Double,
    val cheapHour: Int,
    val cheapHourPriceEurKwh: Double,
    val savingEurPerKwh: Double
)

data class CheapWindow(
    val start: Instant,
    val hours: Int,
    val avgPriceEurKwh: Double
)

data class BillSimulation(
    val month: YearMonth,
    val daysCovered: Int,
    val daysInMonth: Int,
    val partial: Boolean,
    val energyKwh: Double,
    val energyCostEur: Double,
    val powerCostEur: Double,
    val meterRentEur: Double,
    val socialBonusEur: Double,
    val electricityTaxEur: Double,
    val subtotalEur: Double,
    val ivaEur: Double,
    val totalEur: Double,
    val projectedTotalEur: Double?,
    val priceCoverage: Double,
    val missingContract: Boolean
)

enum class PowerRecommendation { OK, OVERSIZED, UNDERSIZED, NO_DATA }

data class MonthlyPower(val month: YearMonth, val maxKwP1: Double, val maxKwP2: Double)

data class PowerAnalysis(
    val months: List<MonthlyPower>,
    val contractedP1Kw: Double?,
    val contractedP2Kw: Double?,
    val recommendation: PowerRecommendation,
    val suggestedP1Kw: Double?
)

data class ExportedFile(val uri: String, val mimeType: String, val fileName: String)
