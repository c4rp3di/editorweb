package com.vigia.app.core

import android.content.Context
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.DnsDao
import com.vigia.app.store.DnsEventEntity
import com.vigia.app.store.SessionDao
import com.vigia.app.store.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Gestor de sesiones: ciclo de vida (nueva, pausa, detener), consultas para la interfaz
 * y borrado. Los módulos de recolección observan [estado] para saber cuándo trabajar.
 */
class SessionManager private constructor(private val app: Context) {

    private val _estado = MutableStateFlow(SesionUi())
    val estado: StateFlow<SesionUi> = _estado.asStateFlow()

    private suspend fun dao(): SessionDao = withContext(Dispatchers.IO) {
        AppDatabase.obtener(app).sessionDao()
    }

    private suspend fun dnsDao(): DnsDao = withContext(Dispatchers.IO) {
        AppDatabase.obtener(app).dnsDao()
    }

    suspend fun cargarSiHaceFalta() {
        if (_estado.value.cargado) return
        try {
            val d = dao()
            val abierta = d.abierta()
            val ultima = d.ultimaCerrada()
            val estadoActual = when {
                abierta == null -> Estado.INACTIVA
                abierta.estado == ESTADO_PAUSADA -> Estado.PAUSADA
                else -> Estado.ACTIVA
            }
            _estado.value = SesionUi(
                cargado = true,
                estado = estadoActual,
                sesionId = abierta?.id,
                inicio = abierta?.inicio,
                vpn = if (abierta != null) vpnDe(abierta.configuracion) else false,
                dns = if (abierta != null) dnsDe(abierta.configuracion) else "sistema",
                ultimaInicio = ultima?.inicio,
                ultimaFin = ultima?.fin
            )
        } catch (e: Exception) {
            _estado.value = _estado.value.copy(cargado = true, error = describir(e))
        }
    }

    suspend fun iniciar(vpn: Boolean, dns: String) {
        cargarSiHaceFalta()
        val actual = _estado.value
        if (actual.estado != Estado.INACTIVA) return
        try {
            val ahora = System.currentTimeMillis()
            val config = JSONObject().put("vpn", vpn).put("dns", dns).toString()
            val id = dao().insertar(
                SessionEntity(inicio = ahora, estado = ESTADO_ACTIVA, configuracion = config)
            )
            _estado.value = actual.copy(
                estado = Estado.ACTIVA,
                sesionId = id,
                inicio = ahora,
                vpn = vpn,
                dns = dns,
                error = null
            )
        } catch (e: Exception) {
            _estado.value = actual.copy(error = describir(e))
        }
    }

    suspend fun alternarPausa() {
        val actual = _estado.value
        val id = actual.sesionId ?: return
        val nuevo = when (actual.estado) {
            Estado.ACTIVA -> Estado.PAUSADA
            Estado.PAUSADA -> Estado.ACTIVA
            else -> return
        }
        try {
            val d = dao()
            val sesion = d.obtener(id)
            if (sesion != null) {
                val texto = if (nuevo == Estado.PAUSADA) ESTADO_PAUSADA else ESTADO_ACTIVA
                d.actualizar(sesion.copy(estado = texto))
            }
            _estado.value = actual.copy(estado = nuevo, error = null)
        } catch (e: Exception) {
            _estado.value = actual.copy(error = describir(e))
        }
    }

    suspend fun detener() {
        val actual = _estado.value
        val id = actual.sesionId ?: return
        try {
            val ahora = System.currentTimeMillis()
            val d = dao()
            val sesion = d.obtener(id)
            if (sesion != null) {
                d.actualizar(sesion.copy(fin = ahora, estado = ESTADO_CERRADA))
            }
            // Fases 4-5: aquí se ejecutará el análisis, se generará el informe y se borrarán los datos crudos.
            _estado.value = SesionUi(
                cargado = true,
                estado = Estado.INACTIVA,
                ultimaInicio = actual.inicio,
                ultimaFin = ahora
            )
        } catch (e: Exception) {
            _estado.value = actual.copy(error = describir(e))
        }
    }

    // ---- Consultas para la interfaz ----

    fun sesiones(): Flow<List<SessionEntity>> = flow {
        emitAll(AppDatabase.obtener(app).sessionDao().observarTodas())
    }.flowOn(Dispatchers.IO)

    fun eventos(sesionId: Long): Flow<List<DnsEventEntity>> = flow {
        emitAll(AppDatabase.obtener(app).dnsDao().observar(sesionId, 5000))
    }.flowOn(Dispatchers.IO)

    suspend fun sesion(id: Long): SessionEntity? {
        return try {
            dao().obtener(id)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun contadores(sesionId: Long): Contadores {
        return try {
            val d = dnsDao()
            Contadores(
                consultas = d.contar(sesionId),
                dominios = d.contarDominios(sesionId),
                apps = d.contarApps(sesionId)
            )
        } catch (e: Exception) {
            Contadores()
        }
    }

    /** Borra una sesión cerrada y todos sus eventos. La sesión abierta no se puede borrar. */
    suspend fun borrarSesion(id: Long) {
        if (_estado.value.sesionId == id) return
        try {
            dnsDao().borrarDeSesion(id)
            dao().borrar(id)
        } catch (e: Exception) {
            _estado.value = _estado.value.copy(error = describir(e))
        }
    }

    private fun describir(e: Exception): String {
        return (e.javaClass.simpleName + ": " + (e.message ?: "sin detalle"))
    }

    companion object {
        const val ESTADO_ACTIVA = "activa"
        const val ESTADO_PAUSADA = "pausada"
        const val ESTADO_CERRADA = "cerrada"

        fun vpnDe(configuracion: String): Boolean {
            return try {
                JSONObject(configuracion).optBoolean("vpn", false)
            } catch (e: Exception) {
                false
            }
        }

        private val DNS_VALIDOS = listOf("sistema", "1.1.1.1", "8.8.8.8", "9.9.9.9")

        fun dnsDe(configuracion: String): String {
            return try {
                val valor = JSONObject(configuracion).optString("dns", "sistema")
                if (DNS_VALIDOS.contains(valor)) valor else "sistema"
            } catch (e: Exception) {
                "sistema"
            }
        }

        @Volatile
        private var instancia: SessionManager? = null

        fun obtener(contexto: Context): SessionManager {
            val actual = instancia
            if (actual != null) return actual
            return synchronized(this) {
                val doble = instancia
                if (doble != null) {
                    doble
                } else {
                    val nueva = SessionManager(contexto.applicationContext)
                    instancia = nueva
                    nueva
                }
            }
        }
    }
}
