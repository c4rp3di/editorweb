package com.luzcontrol.app.data.remote

import com.luzcontrol.app.data.remote.api.DatadisApi
import com.luzcontrol.app.data.remote.dto.*
import com.luzcontrol.app.domain.model.AppError
import com.luzcontrol.app.domain.model.DatadisCredentials
import com.luzcontrol.app.domain.repository.CredentialsRepository
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

/** Rutas de la API privada de Datadis (manual oficial de la API, sección 4). */
object DatadisEndpoints {
    const val SUPPLIES = "get-supplies"
    const val CONTRACT = "get-contract-detail"
    const val CONSUMPTION = "get-consumption-data"
    const val MAX_POWER = "get-max-power"

    fun path(name: String) = "api-private/api/$name"
}

/**
 * Cliente de Datadis respetando sus reglas:
 *  - una sola consulta por combinación de parámetros cada 24 h (ver [ApiCallGuard]); las consultas
 *    bloqueadas devuelven `null` y la app usa los datos ya guardados,
 *  - sin reintentos automáticos de consultas (un error también cuenta como consulta realizada),
 *  - sin repetir logins con credenciales rechazadas, ni mientras el usuario esté bloqueado,
 *  - el token solo vive en memoria; si caduca (401) se renueva una vez.
 */
@Singleton
class DatadisClient @Inject constructor(
    private val api: DatadisApi,
    private val json: Json,
    private val credentials: CredentialsRepository,
    private val guard: ApiCallGuard
) {
    private val lock = Mutex()
    private var token: String? = null
    private var tokenOwner: String? = null

    private fun ownerOf(c: DatadisCredentials) = c.username.trim().uppercase() + ":" + c.password.hashCode()

    private suspend fun login(creds: DatadisCredentials): String {
        if (guard.remainingMs(KEY_BLOCKED, ApiCallGuard.BLOCKED_MS) > 0) throw AppError.AccountBlocked
        val rejectedKey = "login-rejected:" + ownerOf(creds)
        if (guard.remainingMs(rejectedKey, ApiCallGuard.LOGIN_REJECTED_MS) > 0) throw AppError.InvalidCredentials
        val text = try {
            api.login(creds.username.trim().uppercase(), creds.password).string().trim()
        } catch (e: HttpException) {
            val err = mapError(e)
            if (err is AppError.AccountBlocked) {
                guard.mark(KEY_BLOCKED)
                throw err
            }
            if (e.code() in 400..403) {
                guard.mark(rejectedKey)
                throw AppError.InvalidCredentials
            }
            throw err
        } catch (e: Throwable) {
            throw mapError(e)
        }
        if (text.isBlank() || text.startsWith("{") || text.startsWith("<")) {
            guard.mark(rejectedKey)
            throw AppError.InvalidCredentials
        }
        guard.clear(rejectedKey)
        return text.trim('"')
    }

    private suspend fun ensureToken(): String = lock.withLock {
        val creds = credentials.getDatadis() ?: throw AppError.NoCredentials
        val owner = ownerOf(creds)
        val current = token
        if (current != null && owner == tokenOwner) current
        else login(creds).also { token = it; tokenOwner = owner }
    }

    /** Comprueba usuario y contraseña haciendo únicamente el login (no consume consultas de datos). */
    suspend fun validate(creds: DatadisCredentials) {
        login(creds)
    }

    private suspend fun <T> authed(block: suspend (String) -> T): T {
        val t = ensureToken()
        return try {
            block(t)
        } catch (e: AppError.InvalidCredentials) {
            lock.withLock { token = null }
            block(ensureToken())
        }
    }

    /**
     * Ejecuta una consulta de datos como mucho una vez cada 24 h por combinación de parámetros.
     * Devuelve null si se omite por esa regla.
     */
    private suspend fun <T> guarded(key: String, call: suspend () -> List<T>): List<T>? {
        if (guard.remainingMs(key) > 0) return null
        return try {
            call().also { guard.mark(key) }
        } catch (e: AppError) {
            // Si el servidor respondió (aunque sea con error) la consulta cuenta como realizada.
            if (e is AppError.Server || e is AppError.RateLimited || e is AppError.Parse) guard.mark(key)
            if (e is AppError.AccountBlocked) guard.mark(KEY_BLOCKED)
            throw e
        }
    }

    suspend fun supplies(): List<DatadisSupplyDto>? =
        guarded("supplies") {
            authed { t -> fetchList(DatadisSupplyDto.serializer(), DatadisEndpoints.SUPPLIES, t, emptyMap(), "supplies") }
        }

    suspend fun contract(cups: String, distributorCode: String): List<DatadisContractDto>? {
        val params = mapOf("cups" to cups, "distributorCode" to distributorCode)
        return guarded("contract|$cups|$distributorCode") {
            authed { t -> fetchList(DatadisContractDto.serializer(), DatadisEndpoints.CONTRACT, t, params, "contract") }
        }
    }

    /** [month] en formato yyyy/MM. measurementType 0 = horario. */
    suspend fun consumption(cups: String, distributorCode: String, pointType: Int, month: String): List<DatadisConsumptionDto>? {
        val params = mapOf(
            "cups" to cups, "distributorCode" to distributorCode,
            "startDate" to month, "endDate" to month,
            "measurementType" to "0", "pointType" to pointType.toString()
        )
        return guarded("consumption|$cups|$distributorCode|$month|0|$pointType") {
            authed { t -> fetchList(DatadisConsumptionDto.serializer(), DatadisEndpoints.CONSUMPTION, t, params, "timeCurve") }
        }
    }

    suspend fun maxPower(cups: String, distributorCode: String, month: String): List<DatadisMaxPowerDto>? {
        val params = mapOf(
            "cups" to cups, "distributorCode" to distributorCode,
            "startDate" to month, "endDate" to month
        )
        return guarded("maxpower|$cups|$distributorCode|$month") {
            authed { t -> fetchList(DatadisMaxPowerDto.serializer(), DatadisEndpoints.MAX_POWER, t, params, "maxPower") }
        }
    }

    private suspend fun <T> fetchList(
        serializer: KSerializer<T>,
        endpoint: String,
        token: String,
        params: Map<String, String>,
        wrapperKey: String
    ): List<T> {
        val path = DatadisEndpoints.path(endpoint)
        val body = try {
            api.get(path, "Bearer $token", params).string()
        } catch (e: Throwable) {
            throw mapError(e, path)
        }
        return decodeList(serializer, body, wrapperKey)
    }

    /** Acepta un array directo (formato oficial) o un objeto envoltorio. */
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
            if (!text.startsWith("[") && !text.startsWith("{")) emptyList() else throw AppError.Parse(e)
        } catch (e: IllegalArgumentException) {
            throw AppError.Parse(e)
        }
    }

    private fun mapError(e: Throwable, where: String? = null): AppError = when (e) {
        is AppError -> e
        is HttpException -> {
            val body = try {
                e.response()?.errorBody()?.string()?.replace(Regex("\\s+"), " ")?.take(160)
            } catch (_: Exception) {
                null
            }
            when {
                body != null && body.contains("blocked", ignoreCase = true) -> AppError.AccountBlocked
                e.code() == 401 || e.code() == 403 -> AppError.InvalidCredentials
                e.code() == 429 -> AppError.RateLimited(e.response()?.headers()?.get("Retry-After")?.toLongOrNull())
                else -> AppError.Server(e.code(), listOfNotNull(where, body?.takeIf { it.isNotBlank() }).joinToString(" → "))
            }
        }
        is IOException -> AppError.NoNetwork(e)
        is SerializationException -> AppError.Parse(e)
        else -> AppError.Unknown(e)
    }

    private companion object {
        const val KEY_BLOCKED = "login-blocked"
    }
}
