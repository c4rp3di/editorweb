package com.vigia.app.core

import android.content.Context
import com.vigia.app.alert.Alertas
import com.vigia.app.analyze.Analizador
import com.vigia.app.collect.vpn.Escudo
import com.vigia.app.store.BloqueoEntity
import com.vigia.app.informe.InformeGenerador
import com.vigia.app.store.A11yEventEntity
import com.vigia.app.store.AnalisisDao
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.DnsDao
import com.vigia.app.store.DnsEventEntity
import com.vigia.app.store.EventosDao
import com.vigia.app.store.FindingEntity
import com.vigia.app.store.InventarioSnapshotEntity
import com.vigia.app.store.NetDao
import com.vigia.app.store.NetEventEntity
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

    private suspend fun analisisDao(): AnalisisDao = withContext(Dispatchers.IO) {
        AppDatabase.obtener(app).analisisDao()
    }

    private suspend fun netDao(): NetDao = withContext(Dispatchers.IO) {
        AppDatabase.obtener(app).netDao()
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
                red = if (abierta != null) redDe(abierta.configuracion) else false,
                bloquearQuic = if (abierta != null) quicDe(abierta.configuracion) else true,
                modulos = if (abierta != null) modulosDe(abierta.configuracion) else Modulos(),
                ultimaId = ultima?.id,
                ultimaInicio = ultima?.inicio,
                ultimaFin = ultima?.fin
            )
        } catch (e: Exception) {
            _estado.value = _estado.value.copy(cargado = true, error = describir(e))
        }
    }

    suspend fun iniciar(vpn: Boolean, dns: String, modulos: Modulos, red: Boolean, bloquearQuic: Boolean) {
        cargarSiHaceFalta()
        val actual = _estado.value
        if (actual.estado != Estado.INACTIVA) return
        try {
            val ahora = System.currentTimeMillis()
            val config = JSONObject()
                .put("vpn", vpn)
                .put("dns", dns)
                .put("red", red && vpn)
                .put("bloquear_quic", bloquearQuic)
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
                red = red && vpn,
                bloquearQuic = bloquearQuic,
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
                ultimaId = id,
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
        val red = bd.netDao().observar(sesionId, 3000)
        emitAll(
            combine(dns, sistema, sensores, pantalla, red) { a, b, c, d, e -> unir(a, b, c, d, e) }
        )
    }.flowOn(Dispatchers.IO)

    private fun unir(
        dns: List<DnsEventEntity>,
        sistema: List<SysEventEntity>,
        sensores: List<SensorEventEntity>,
        pantalla: List<A11yEventEntity>,
        red: List<NetEventEntity>
    ): List<FilaLinea> {
        val lista = ArrayList<FilaLinea>(dns.size + sistema.size + sensores.size + pantalla.size + red.size)
        for (e in dns) {
            lista.add(FilaLinea(e.ts, "DNS", e.tipo, e.app, e.dominio + " → " + e.respuesta, e.dominio, ""))
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
        for (e in red) {
            val destino = if (e.sni.isNotEmpty()) e.sni + " (" + e.ip + ":" + e.puerto + ")" else e.ip + ":" + e.puerto
            lista.add(FilaLinea(e.ts, "RED", e.proto, e.app, destino + " · ↑ " + resumirBytes(e.bytesOut) + " ↓ " + resumirBytes(e.bytesIn), e.sni, e.ip))
        }
        lista.sortByDescending { it.ts }
        return if (lista.size > 6000) ArrayList(lista.subList(0, 6000)) else lista
    }

    private fun resumirBytes(n: Long): String {
        return if (n < 1024L) n.toString() + " B" else if (n < 1024L * 1024L) (n / 1024L).toString() + " KB" else String.format("%.1f MB", n / 1048576.0)
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
            val an = analisisDao()
            val nd = netDao()
            Contadores(
                consultas = d.contar(sesionId),
                dominios = d.contarDominios(sesionId),
                apps = d.contarApps(sesionId),
                sistema = ev.contarSys(sesionId),
                sensores = ev.contarSensor(sesionId),
                pantalla = ev.contarA11y(sesionId),
                hallazgos = an.contarFindings(sesionId),
                hallazgosAltos = an.contarFindingsAltos(sesionId),
                conexiones = nd.contar(sesionId),
                destinos = nd.contarDestinos(sesionId),
                bytes = nd.sumaBytes(sesionId)
            )
        } catch (e: Exception) {
            Contadores()
        }
    }

    fun hallazgos(sesionId: Long): Flow<List<FindingEntity>> = flow {
        emitAll(AppDatabase.obtener(app).analisisDao().observarFindings(sesionId))
    }.flowOn(Dispatchers.IO)

    suspend fun hallazgo(id: Long): FindingEntity? {
        return try {
            analisisDao().finding(id)
        } catch (e: Exception) {
            null
        }
    }

    /** Ejecuta el análisis completo (firmas, línea base, heurísticas y correlación) sobre una sesión. */
    suspend fun analizar(sesionId: Long, alertar: Boolean = false): Int {
        val n = try {
            Analizador.ejecutar(app, sesionId)
        } catch (e: Exception) {
            _estado.value = _estado.value.copy(error = "Análisis: " + describir(e))
            0
        }
        if (alertar) {
            try {
                Alertas.procesar(app, sesionId)
            } catch (e: Exception) {
            }
        }
        return n
    }

    /** Fin de sesión: análisis, alertas, informe firmado y, si el usuario lo eligió, borrado de datos crudos. */
    suspend fun finalizar(sesionId: Long) {
        analizar(sesionId, true)
        try {
            InformeGenerador.generarYGuardar(app, sesionId)
        } catch (e: Exception) {
            _estado.value = _estado.value.copy(error = "Informe: " + describir(e))
        }
        try {
            Retencion.aplicarAlCerrar(app, sesionId)
        } catch (e: Exception) {
        }
    }

    // ---- Escudo ----

    fun bloqueos(): Flow<List<BloqueoEntity>> = flow {
        emitAll(AppDatabase.obtener(app).bloqueosDao().observar())
    }.flowOn(Dispatchers.IO)

    /** Añade un bloqueo. Devuelve false si el valor no es un dominio o una IP válidos. */
    suspend fun bloquear(tipo: String, entrada: String, nota: String): Boolean {
        val candidato: String? = if (tipo == "ip") {
            if (Escudo.parseIp(entrada) != null) entrada.trim() else null
        } else {
            Escudo.normalizarDominio(entrada)
        }
        val valor = candidato ?: return false
        return try {
            withContext(Dispatchers.IO) {
                AppDatabase.obtener(app).bloqueosDao().insertar(
                    BloqueoEntity(tipo = tipo, valor = valor, creado = System.currentTimeMillis(), nota = nota)
                )
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun desbloquear(id: Long) {
        try {
            withContext(Dispatchers.IO) { AppDatabase.obtener(app).bloqueosDao().borrar(id) }
        } catch (e: Exception) {
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
            netDao().borrarDeSesion(id)
            val an = analisisDao()
            an.borrarFindings(id)
            an.borrarMuestrasDeSesion(id)
            val informes = withContext(Dispatchers.IO) { AppDatabase.obtener(app).informesDao() }
            informes.borrarInforme(id)
            informes.borrarAlertas(id)
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

        /** Momento en que se borraron los datos crudos de la sesión (0 si todavía existen). */
        fun crudosBorradosDe(configuracion: String): Long {
            return try {
                JSONObject(configuracion).optLong("crudos_borrados", 0L)
            } catch (e: Exception) {
                0L
            }
        }

        fun redDe(configuracion: String): Boolean {
            return try {
                JSONObject(configuracion).optBoolean("red", false)
            } catch (e: Exception) {
                false
            }
        }

        fun quicDe(configuracion: String): Boolean {
            return try {
                JSONObject(configuracion).optBoolean("bloquear_quic", true)
            } catch (e: Exception) {
                true
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
