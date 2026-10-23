package com.luzcontrol.app.data.remote

import com.luzcontrol.app.data.remote.api.EsiosApi
import com.luzcontrol.app.data.remote.api.ReeApi
import com.luzcontrol.app.data.remote.dto.EsiosResponseDto
import com.luzcontrol.app.data.remote.dto.ReeResponseDto
import com.luzcontrol.app.data.remote.dto.ReeValueDto
import com.luzcontrol.app.domain.model.AppError
import com.luzcontrol.app.domain.model.PricePoint
import com.luzcontrol.app.domain.model.TariffCalendar
import com.luzcontrol.app.domain.repository.CredentialsRepository
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Precios PVPC / spot por hora.
 *  - Con token de ESIOS: api.esios.ree.es (indicadores 1001 = PVPC 2.0TD y 600 = spot, geo 8741 = península).
 *  - Sin token: endpoint público apidatos.ree.es (misma información, sin autenticación).
 * Si la fuente publica en cuartos de hora, se promedia a horas.
 */
@Singleton
class PriceRemoteSource @Inject constructor(
    private val esios: EsiosApi,
    private val ree: ReeApi,
    private val json: Json,
    private val credentials: CredentialsRepository
) {
    suspend fun fetch(from: LocalDate, to: LocalDate): List<PricePoint> {
        val token = credentials.getEsiosToken()
        return if (token != null) fetchEsios(token, from, to) else fetchRee(from, to)
    }

    // ---------------- ESIOS (con token) ----------------

    private suspend fun fetchEsios(token: String, from: LocalDate, to: LocalDate): List<PricePoint> {
        val zone = TariffCalendar.ZONE
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val start = from.atStartOfDay(zone).format(fmt)
        val end = to.plusDays(1).atStartOfDay(zone).minusSeconds(1).format(fmt)
        val pvpc = esiosSeries(token, 1001, start, end)
        val spot = try { esiosSeries(token, 600, start, end) } catch (e: AppError) { emptyMap() }
        return pvpc.map { (h, v) -> PricePoint(h, v, spot[h]) }.sortedBy { it.start }
    }

    private suspend fun esiosSeries(token: String, id: Int, start: String, end: String): Map<Instant, Double> {
        val body = call { esios.indicator(id = id, apiKey = token, startDate = start, endDate = end).string() }
        val dto = try {
            json.decodeFromString(EsiosResponseDto.serializer(), body)
        } catch (e: SerializationException) {
            throw AppError.Parse(e)
        }
        val values = dto.indicator?.values.orEmpty().filter { it.geoId == null || it.geoId == 8741L }
        return averageByHour(values.mapNotNull { v ->
            val t = parseInstant(v.datetime) ?: return@mapNotNull null
            val value = v.value ?: return@mapNotNull null
            t to value / 1000.0 // €/MWh → €/kWh
        })
    }

    // ---------------- REE apidatos (sin token) ----------------

    private suspend fun fetchRee(from: LocalDate, to: LocalDate): List<PricePoint> {
        val zone = TariffCalendar.ZONE
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val pvpc = LinkedHashMap<Instant, Double>()
        val spot = LinkedHashMap<Instant, Double>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            val chunkEnd = minOf(cursor.plusDays(6), to)
            val start = cursor.atStartOfDay(zone).format(fmt)
            val end = chunkEnd.plusDays(1).atStartOfDay(zone).minusMinutes(1).format(fmt)
            val body = call { ree.realTimePrices(start, end).string() }
            val dto = try {
                json.decodeFromString(ReeResponseDto.serializer(), body)
            } catch (e: SerializationException) {
                throw AppError.Parse(e)
            }
            for (series in dto.included) {
                val id = series.id.orEmpty()
                val label = (series.attributes?.title.orEmpty() + " " + series.type.orEmpty()).lowercase()
                val values = series.attributes?.values.orEmpty()
                when {
                    id == "1001" || label.contains("pvpc") -> pvpc += toHourly(values)
                    id == "600" || label.contains("spot") -> spot += toHourly(values)
                }
            }
            cursor = chunkEnd.plusDays(1)
            if (!cursor.isAfter(to)) delay(500)
        }
        return pvpc.map { (h, v) -> PricePoint(h, v, spot[h]) }.sortedBy { it.start }
    }

    private fun toHourly(values: List<ReeValueDto>): Map<Instant, Double> =
        averageByHour(values.mapNotNull { v ->
            val t = parseInstant(v.datetime) ?: return@mapNotNull null
            val value = v.value ?: return@mapNotNull null
            t to value / 1000.0
        })

    // ---------------- utilidades ----------------

    internal fun averageByHour(samples: List<Pair<Instant, Double>>): Map<Instant, Double> =
        samples.groupBy({ it.first.truncatedTo(ChronoUnit.HOURS) }, { it.second })
            .mapValues { (_, v) -> v.average() }
            .toSortedMap()

    private fun parseInstant(text: String?): Instant? = try {
        if (text.isNullOrBlank()) null else OffsetDateTime.parse(text).toInstant()
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> call(block: suspend () -> T): T {
        var attempt = 0
        var wait = 2_000L
        while (true) {
            try {
                return block()
            } catch (e: HttpException) {
                val err: AppError = when (e.code()) {
                    401, 403 -> AppError.Server(e.code(), "token de ESIOS no válido")
                    429 -> AppError.RateLimited()
                    else -> AppError.Server(e.code(), e.message())
                }
                attempt++
                val retry = err is AppError.RateLimited || (err is AppError.Server && err.code >= 500)
                if (!retry || attempt >= 3) throw err
                delay(wait); wait *= 2
            } catch (e: IOException) {
                throw AppError.NoNetwork(e)
            }
        }
    }
}
