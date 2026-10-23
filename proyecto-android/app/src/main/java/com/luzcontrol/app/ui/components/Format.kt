package com.luzcontrol.app.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.luzcontrol.app.domain.model.TariffCalendar

object Fmt {
    val es: Locale = Locale.forLanguageTag("es-ES")
    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val shortDate = DateTimeFormatter.ofPattern("dd/MM")
    private val dateTime = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    private val zone: ZoneId = TariffCalendar.ZONE

    fun kwh(v: Double) = String.format(es, "%.2f kWh", v)
    fun kwh1(v: Double) = String.format(es, "%.1f kWh", v)
    fun eur(v: Double) = String.format(es, "%.2f €", v)
    fun price(v: Double) = String.format(es, "%.4f €/kWh", v)
    fun price3(v: Double) = String.format(es, "%.3f", v)
    fun kw(v: Double) = String.format(es, "%.2f kW", v)
    fun watts(v: Double) = String.format(es, "%.0f W", v)
    fun pct(v: Double) = String.format(es, "%.0f %%", v)
    fun num(v: Double, decimals: Int = 2) = String.format(es, "%.${decimals}f", v)
    fun date(d: LocalDate): String = d.format(dateFmt)
    fun shortDate(d: LocalDate): String = d.format(shortDate)
    fun dateTime(i: Instant): String = i.atZone(zone).format(dateTime)
    fun hour(i: Instant): String = String.format(es, "%02d:00", i.atZone(zone).hour)
    fun hourOfDay(h: Int): String = String.format(es, "%02d:00", h)

    fun ago(i: Instant, now: Instant = Instant.now()): String {
        val minutes = java.time.Duration.between(i, now).toMinutes()
        return when {
            minutes < 1 -> "hace un momento"
            minutes < 60 -> "hace $minutes min"
            minutes < 60 * 24 -> "hace ${minutes / 60} h"
            else -> "hace ${minutes / (60 * 24)} d"
        }
    }

    /** Acepta "0,12" y "0.12". */
    fun parseDouble(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
}
