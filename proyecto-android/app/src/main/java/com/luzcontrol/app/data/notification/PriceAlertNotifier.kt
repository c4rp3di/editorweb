package com.luzcontrol.app.data.notification

import com.luzcontrol.app.domain.model.PricePoint
import com.luzcontrol.app.domain.model.TariffCalendar
import com.luzcontrol.app.domain.repository.EnergyRepository
import com.luzcontrol.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Avisa si en las próximas 24 h hay horas muy baratas (percentil < 10) o muy caras (percentil > 90). */
@Singleton
class PriceAlertNotifier @Inject constructor(
    private val energy: EnergyRepository,
    private val settings: SettingsRepository,
    private val helper: NotificationHelper
) {
    suspend fun checkAndNotify(now: Instant = Instant.now()) {
        if (!settings.settings.value.priceNotifications) return
        val hourStart = now.truncatedTo(ChronoUnit.HOURS)
        val all = energy.observePrices(hourStart.minus(30, ChronoUnit.DAYS), hourStart.plus(48, ChronoUnit.HOURS)).first()
        val upcoming = all.filter { it.start >= hourStart && it.start < hourStart.plus(24, ChronoUnit.HOURS) }
        val history = all.filter { it.start < hourStart }
        if (upcoming.isEmpty() || history.size < 48) return

        val sorted = history.map { it.pvpcEurKwh }.sorted()
        val p10 = sorted[(sorted.size * 0.10).toInt().coerceIn(0, sorted.lastIndex)]
        val p90 = sorted[(sorted.size * 0.90).toInt().coerceIn(0, sorted.lastIndex)]

        val lines = ArrayList<String>()
        val next = upcoming.first()
        val cheapest = upcoming.minByOrNull { it.pvpcEurKwh }!!
        val dearest = upcoming.maxByOrNull { it.pvpcEurKwh }!!

        if (next.pvpcEurKwh <= p10) {
            lines += "Ahora la luz está muy barata: ${price(next)}. Buen momento para lavadora o lavavajillas."
        } else if (cheapest.pvpcEurKwh <= p10) {
            lines += "Hora muy barata a las ${hour(cheapest)}: ${price(cheapest)}."
        }
        if (dearest.pvpcEurKwh >= p90) {
            lines += "Evita las ${hour(dearest)}: precio muy alto (${price(dearest)})."
        }
        if (lines.isNotEmpty()) helper.postPriceAlert("Precio de la luz", lines.joinToString("\n"))
    }

    private fun hour(p: PricePoint): String =
        String.format(Locale.forLanguageTag("es-ES"), "%02d:00", p.start.atZone(TariffCalendar.ZONE).hour)

    private fun price(p: PricePoint): String =
        String.format(Locale.forLanguageTag("es-ES"), "%.3f €/kWh", p.pvpcEurKwh)
}
