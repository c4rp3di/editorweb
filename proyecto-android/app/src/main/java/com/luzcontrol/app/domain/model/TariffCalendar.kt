package com.luzcontrol.app.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendario de periodos de la tarifa 2.0TD (hora peninsular). */
object TariffCalendar {
    val ZONE: ZoneId = ZoneId.of("Europe/Madrid")

    // Festivos de ámbito nacional no sustituibles (mes, día)
    private val NATIONAL_HOLIDAYS = setOf(
        1 to 1, 1 to 6, 5 to 1, 8 to 15, 10 to 12, 11 to 1, 12 to 6, 12 to 8, 12 to 25
    )

    fun isNationalHoliday(date: LocalDate): Boolean =
        (date.monthValue to date.dayOfMonth) in NATIONAL_HOLIDAYS

    fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    fun periodAt(date: LocalDate, hour: Int): TariffPeriod {
        if (isWeekend(date) || isNationalHoliday(date)) return TariffPeriod.P3
        return when (hour) {
            in 0..7 -> TariffPeriod.P3
            8, 9, in 14..17, 22, 23 -> TariffPeriod.P2
            else -> TariffPeriod.P1 // 10-13 y 18-21
        }
    }

    fun periodAt(instant: Instant): TariffPeriod {
        val z = instant.atZone(ZONE)
        return periodAt(z.toLocalDate(), z.hour)
    }
}
