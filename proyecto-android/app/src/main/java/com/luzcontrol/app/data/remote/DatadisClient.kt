package com.luzcontrol.app.data.remote

import com.luzcontrol.app.data.remote.api.DatadisApi
import com.luzcontrol.app.data.remote.dto.*
import com.luzcontrol.app.domain.model.AppError
import com.luzcontrol.app.domain.model.DatadisCredentials
import com.luzcontrol.app.domain.repository.CredentialsRepository
import com.luzcontrol.app.domain.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Rutas de la API privada de Datadis (v1 y v2). */
object DatadisEndpoints {
    const val SUPPLIES = "get-supplies"
    const val CONTRACT = "get-contract-detail"
    const val CONSUMPTION = "get-consumption-data"
    const val MAX_POWER = "get-max-power"

    fun path(name: String, v2: Boolean) = "api-private/api/" + if (v2) "$name-v2" else name
}

/**
 * Cliente de Datadis con sesión en memoria:
 *  - el token solo vive en memoria; si caduca (401) se renueva una vez con las credenciales cifradas,
 *  - 429 / 5xx → reintento con retroceso exponencial dentro de la misma llamada,
 *  - si la versión elegida (v1/v2) responde 404/410 se prueba la otra.
 */
@Singleton
class DatadisClient @Inject constructor(
    private val api: DatadisApi,
    private val json: Json,
    private val credentials: CredentialsRepository,
    private val settings: SettingsRepository
) {
    private val lock = Mutex()
    private var token: String? = null
    private var tokenOwner: String? = null

    /** Descarta el token en memoria (p. ej. al cambiar de credenciales). */
    fun invalidate() { token = null }

    private suspend fun login(creds: DatadisCredentials): String {
        val text = try {
            api.login(creds.username.trim(), creds.password).string().trim()
        } catch (e: Throwable) {
            throw mapError(e)
        }
        if (text.isBlank() || text.startsWith("{") || text.startsWith("<")) throw AppError.InvalidCredentials
        return text.trim('"')
    }

    private suspend fun ensureToken(): String = lock.withLock {
        val creds = credentials.getDatadis() ?: throw AppError.NoCredentials
        val owner = creds.username + ":" + creds.password.hashCode()
        val current = token
        if (current != null && owner == tokenOwner) current
        else login(creds).also { token = it; tokenOwner = owner }
    }

    /** Valida usuario y contraseña (login + get-supplies) sin tocar las credenciales guardadas. Devuelve nº de suministros. */
    suspend fun validate(creds: DatadisCredentials): Int {
        val t = login(creds)
        val v2 = settings.settings.value.datadisApiV2
        return fetchList(DatadisSupplyDto.serializer(), DatadisEndpoints.SUPPLIES, v2, t, emptyMap(), "supplies").size
    }

    private suspend fun <T> authed(block: suspend (String) -> T): T {
        val t = ensureToken()
        return try {
            block(t)
        } catch (e: AppError.InvalidCredentials) {
            lock.withLock { token = null }
            val fresh = ensureToken()
            block(fresh)
        }
    }

    suspend fun supplies(): List<DatadisSupplyDto> = authed { t ->
        fetchWithFallback(DatadisSupplyDto.serializer(), DatadisEndpoints.SUPPLIES, t, emptyMap(), "supplies")
    }

    suspend fun contract(cups: String, distributorCode: String): List<DatadisContractDto> = authed { t ->
        fetchWithFallback(
            DatadisContractDto.serializer(), DatadisEndpoints.CONTRACT, t,
            mapOf("cups" to cups, "distributorCode" to distributorCode), "contract"
        )
    }

    /** [month] en formato yyyy/MM. measurementType 0 = horaria. */
    suspend fun consumption(cups: String, distributorCode: String, pointType: Int, month: String): List<DatadisConsumptionDto> =
        authed { t ->
            fetchWithFallback(
                DatadisConsumptionDto.serializer(), DatadisEndpoints.CONSUMPTION, t,
                mapOf(
                    "cups" to cups,
                    "distributorCode" to distributorCode,
                    "startDate" to month,
                    "endDate" to month,
                    "measurementType" to "0",
                    "pointType" to pointType.toString()
                ),
                "timeCurve"
            )
        }

    suspend fun maxPower(cups: String, distributorCode: String, month: String): List<DatadisMaxPowerDto> =
        authed { t ->
            fetchWithFallback(
                DatadisMaxPowerDto.serializer(), DatadisEndpoints.MAX_POWER, t,
                mapOf(
                    "cups" to cups,
                    "distributorCode" to distributorCode,
                    "startDate" to month,
                    "endDate" to month
                ),
                "maxPower"
            )
        }

    private suspend fun <T> fetchWithFallback(
        serializer: KSerializer<T>,
        endpoint: String,
        token: String,
        params: Map<String, String>,
        wrapperKey: String
    ): List<T> {
        val preferV2 = settings.settings.value.datadisApiV2
        return try {
            fetchList(serializer, endpoint, preferV2, token, params, wrapperKey)
        } catch (e: AppError.Server) {
            if (e.code == 404 || e.code == 410) {
                fetchList(serializer, endpoint, !preferV2, token, params, wrapperKey)
            } else throw e
        }
    }

    private suspend fun <T> fetchList(
        serializer: KSerializer<T>,
        endpoint: String,
        v2: Boolean,
        token: String,
        params: Map<String, String>,
        wrapperKey: String
    ): List<T> {
        val body = withBackoff {
            try {
                api.get(DatadisEndpoints.path(endpoint, v2), "Bearer $token", params).string()
            } catch (e: Throwable) {
                throw mapError(e)
            }
        }
        return decodeList(serializer, body, wrapperKey)
    }

    /** Acepta un array directo (v1) o un objeto envoltorio (v2: supplies / contract / timeCurve / maxPower). */
    internal fun <T> decodeList(serializer: KSerializer<T>, body: String, wrapperKey: String): List<T> {
        val text = body.trim()
        if (text.isEmpty()) return emptyList()
        return try {
            val element: JsonElement = json.parseToJsonElement(text)
            val array: JsonArray? = when (element) {
                is JsonArray -> element
                is JsonObject -> (element[wrapperKey] as? JsonArray)
                    ?: element.values.firstOrNull { it is JsonArray && it.isNotEmpty() && it.first() is JsonObject } as? JsonArray
                else -> null
            }
            if (array == null) emptyList()
            else json.decodeFromJsonElement(ListSerializer(serializer), array)
        } catch (e: SerializationException) {
            // Mensajes de error con código 200 ("No hay datos", etc.) → lista vacía
            if (!text.startsWith("[") && !text.startsWith("{")) emptyList() else throw AppError.Parse(e)
        } catch (e: IllegalArgumentException) {
            throw AppError.Parse(e)
        }
    }

    private suspend fun <T> withBackoff(maxAttempts: Int = 3, baseDelayMs: Long = 2_000, block: suspend () -> T): T {
        var attempt = 0
        var wait = baseDelayMs
        while (true) {
            try {
                return block()
            } catch (e: AppError) {
                val retryable = e is AppError.RateLimited || (e is AppError.Server && e.code >= 500)
                attempt++
                if (!retryable || attempt >= maxAttempts) throw e
                delay(wait)
                wait *= 2
            }
        }
    }

    private fun mapError(e: Throwable): AppError = when (e) {
        is AppError -> e
        is HttpException -> when (e.code()) {
            401, 403 -> AppError.InvalidCredentials
            429 -> AppError.RateLimited(e.response()?.headers()?.get("Retry-After")?.toLongOrNull())
            else -> AppError.Server(e.code(), e.message())
        }
        is IOException -> AppError.NoNetwork(e)
        is SerializationException -> AppError.Parse(e)
        else -> AppError.Unknown(e)
    }
}
