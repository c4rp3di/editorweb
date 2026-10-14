package com.vigia.app.core

import android.content.Context
import com.vigia.app.store.A11yEventEntity
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.DnsDao
import com.vigia.app.store.DnsEventEntity
import com.vigia.app.store.EventosDao
import com.vigia.app.store.InventarioSnapshotEntity
import com.vigia.app.store.SensorEventEntity
import com.vigia.app.store.SessionDao
import com.vigia.app.store.SessionEntity
import com.vigia.app.store.SysEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    private suspend fun eventosDao(): EventosDao = withContext(Dispatchers.IO) {
        AppDatabase.obtener(app).eventosDao()
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
                modulos = if (abierta != null) modulosDe(abierta.configuracion) else Modulos(),
                ultimaInicio = ultima?.inicio,
                ultimaFin = ultima?.fin
            )
        } catch (e: Exception) {
            _estado.value = _estado.value.copy(cargado = true, error = describir(e))
        }
    }

    suspend fun iniciar(vpn: Boolean, dns: String, modulos: Modulos) {
        cargarSiHaceFalta()
        val actual = _estado.value
        if (actual.estado != Estado.INACTIVA) return
        try {
            val ahora = System.currentTimeMillis()
            val config = JSONObject()
                .put("vpn", vpn)
                .put("dns", dns)
                .put("eventos", modulos.eventos)
                .put("inventario", modulos.inventario)
                .put("sensores", modulos.sensores)
                .put("a11y", modulos.a11y)
                .toString()
            val id = dao().insertar(
                SessionEntity(inicio = ahora, estado = ESTADO_ACTIVA, configuracion = config)
            )
            _estado.value = actual.copy(
                estado = Estado.ACTIVA,
                sesionId = id,
                inicio = ahora,
                vpn = vpn,
                dns = dns,
                modulos = modulos,
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

    /** Todo lo registrado en la sesión, mezclado y ordenado por hora (lo más reciente primero). */
    fun lineaTiempo(sesionId: Long): Flow<List<FilaLinea>> = flow {
        val bd = AppDatabase.obtener(app)
        val dns = bd.dnsDao().observar(sesionId, 3000)
        val sistema = bd.eventosDao().observarSys(sesionId, 3000)
        val sensores = bd.eventosDao().observarSensor(sesionId, 3000)
        val pantalla = bd.eventosDao().observarA11y(sesionId, 3000)
        emitAll(
            combine(dns, sistema, sensores, pantalla) { a, b, c, d -> unir(a, b, c, d) }
        )
    }.flowOn(Dispatchers.IO)

    private fun unir(
        dns: List<DnsEventEntity>,
        sistema: List<SysEventEntity>,
        sensores: List<SensorEventEntity>,
        pantalla: List<A11yEventEntity>
    ): List<FilaLinea> {
        val lista = ArrayList<FilaLinea>(dns.size + sistema.size + sensores.size + pantalla.size)
        for (e in dns) {
            lista.add(FilaLinea(e.ts, "DNS", e.tipo, e.app, e.dominio + " → " + e.respuesta))
        }
        for (e in sistema) {
            lista.add(FilaLinea(e.ts, "SISTEMA", e.tipo, paqueteDe(e.payload), com.vigia.app.util.Formato.payload(e.payload)))
        }
        for (e in sensores) {
            lista.add(FilaLinea(e.ts, "SENSOR", e.sensor, e.app, e.modo))
        }
        for (e in pantalla) {
            lista.add(FilaLinea(e.ts, "PANTALLA", e.tipoEvento, e.app, e.detalle))
        }
        lista.sortByDescending { it.ts }
        return if (lista.size > 6000) ArrayList(lista.subList(0, 6000)) else lista
    }

    private fun paqueteDe(payload: String): String {
        return try {
            JSONObject(payload).optString("paquete", "")
        } catch (e: Exception) {
            ""
        }
    }

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
            val ev = eventosDao()
            Contadores(
                consultas = d.contar(sesionId),
                dominios = d.contarDominios(sesionId),
                apps = d.contarApps(sesionId),
                sistema = ev.contarSys(sesionId),
                sensores = ev.contarSensor(sesionId),
                pantalla = ev.contarA11y(sesionId)
            )
        } catch (e: Exception) {
            Contadores()
        }
    }

    /** Última foto del inventario de la sesión y número de fotos guardadas. */
    suspend fun inventario(sesionId: Long): Pair<InventarioSnapshotEntity?, Int> {
        return try {
            val inv = withContext(Dispatchers.IO) { AppDatabase.obtener(app).inventarioDao() }
            Pair(inv.ultimo(sesionId), inv.contar(sesionId))
        } catch (e: Exception) {
            Pair(null, 0)
        }
    }

    /** Borra una sesión cerrada y todo lo registrado en ella. La sesión abierta no se puede borrar. */
    suspend fun borrarSesion(id: Long) {
        if (_estado.value.sesionId == id) return
        try {
            dnsDao().borrarDeSesion(id)
            val ev = eventosDao()
            ev.borrarSys(id)
            ev.borrarSensor(id)
            ev.borrarA11y(id)
            withContext(Dispatchers.IO) { AppDatabase.obtener(app).inventarioDao() }.borrar(id)
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

        fun modulosDe(configuracion: String): Modulos {
            return try {
                val o = JSONObject(configuracion)
                Modulos(
                    eventos = o.optBoolean("eventos", false),
                    inventario = o.optBoolean("inventario", false),
                    sensores = o.optBoolean("sensores", false),
                    a11y = o.optBoolean("a11y", false)
                )
            } catch (e: Exception) {
                Modulos()
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
