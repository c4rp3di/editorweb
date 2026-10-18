package com.vigia.app.informe

import android.content.Context
import com.vigia.app.analyze.Severidad
import com.vigia.app.analyze.base.MotorBase
import com.vigia.app.core.SessionManager
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.InformeEntity
import com.vigia.app.util.Formato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Informe de sesión: resumen ejecutivo, hallazgos con gravedad y confianza, línea base de la sesión y
 * estadísticas agregadas. No incluye datos crudos salvo que el usuario lo pida al exportar.
 */
object InformeGenerador {
    private const val MAX_CRUDOS = 20000

    /** Construye el contenido (JSON en texto) a partir de lo que hay en la base. Devuelve null si no existe la sesión. */
    suspend fun construir(contexto: Context, sesionId: Long, incluirCrudos: Boolean): String? = withContext(Dispatchers.IO) {
        val app = contexto.applicationContext
        val bd = AppDatabase.obtener(app)
        val sesion = bd.sessionDao().obtener(sesionId) ?: return@withContext null
        val analisis = bd.analisisDao()
        val eventos = bd.eventosDao()
        val dnsDao = bd.dnsDao()

        val modulos = SessionManager.modulosDe(sesion.configuracion)
        val vpn = SessionManager.vpnDe(sesion.configuracion)
        val fin = sesion.fin ?: System.currentTimeMillis()
        val hallazgos = analisis.listaFindings(sesionId)
            .sortedWith(compareByDescending<com.vigia.app.store.FindingEntity> { Severidad.peso(it.severidad) }.thenBy { it.ts })

        val criticas = hallazgos.count { it.severidad == Severidad.CRITICA }
        val altas = hallazgos.count { it.severidad == Severidad.ALTA }
        val medias = hallazgos.count { it.severidad == Severidad.MEDIA }
        val bajas = hallazgos.count { it.severidad == Severidad.BAJA }
        val info = hallazgos.count { it.severidad == Severidad.INFO }
        val iocs = analisis.contarIocs()

        val resumen = StringBuilder()
        resumen.append("Sesión de ").append(Formato.duracion(fin - sesion.inicio)).append(" (")
            .append(Formato.fecha(sesion.inicio)).append("). Se generaron ")
            .append(criticas + altas + medias + bajas).append(" hallazgo(s): ")
            .append(criticas).append(" críticos, ").append(altas).append(" altos, ")
            .append(medias).append(" medios y ").append(bajas).append(" bajos. ")
        if (iocs == 0) {
            resumen.append("No había listas de indicadores cargadas, así que no se comparó con ellas. ")
        } else {
            resumen.append("Se compararon los datos con ").append(iocs).append(" indicadores públicos. ")
        }
        resumen.append("Un informe sin hallazgos no demuestra que el teléfono esté limpio: las listas públicas ")
            .append("no cubren el spyware reciente ni el de recursos estatales.")

        val raiz = JSONObject()
        raiz.put("version", 1)
        raiz.put("generado", System.currentTimeMillis())
        raiz.put("aplicacion", "Vigía")
        raiz.put(
            "sesion",
            JSONObject()
                .put("id", sesionId)
                .put("inicio", sesion.inicio)
                .put("fin", fin)
                .put("cerrada", sesion.fin != null)
                .put("captura_dns", vpn)
                .put("servidor_dns", SessionManager.dnsDe(sesion.configuracion))
                .put(
                    "modulos",
                    JSONObject().put("eventos", modulos.eventos).put("inventario", modulos.inventario)
                        .put("sensores", modulos.sensores).put("accesibilidad", modulos.a11y)
                )
        )
        raiz.put(
            "resumen",
            JSONObject().put("criticas", criticas).put("altas", altas).put("medias", medias)
                .put("bajas", bajas).put("informativas", info).put("texto", resumen.toString())
        )

        val lista = JSONArray()
        for (h in hallazgos) {
            lista.put(
                JSONObject()
                    .put("ts", h.ts).put("severidad", h.severidad).put("modulo", h.modulo).put("regla", h.regla)
                    .put("app", h.app).put("titulo", h.titulo).put("descripcion", h.descripcion)
                    .put("confianza", h.confianza)
                    .put("pruebas", try { JSONArray(h.evidenciaIds).length() } catch (e: Exception) { 0 })
            )
        }
        raiz.put("hallazgos", lista)

        // ---- estadísticas agregadas ----
        val stats = JSONObject()
        stats.put("consultas_dns", dnsDao.contar(sesionId))
        stats.put("dominios_unicos", dnsDao.contarDominios(sesionId))
        stats.put("apps_con_dns", dnsDao.contarApps(sesionId))
        stats.put("eventos_sistema", eventos.contarSys(sesionId))
        stats.put("eventos_sensores", eventos.contarSensor(sesionId))
        stats.put("eventos_pantalla", eventos.contarA11y(sesionId))
        val top = JSONArray()
        for (a in dnsDao.topApps(sesionId, 10)) top.put(JSONObject().put("app", a.app).put("consultas", a.n))
        stats.put("top_apps_dns", top)
        if (SessionManager.redDe(sesion.configuracion)) {
            val net = bd.netDao()
            val red = JSONObject()
            red.put("conexiones", net.contar(sesionId))
            red.put("destinos_unicos", net.contarDestinos(sesionId))
            red.put("bytes_totales", net.sumaBytes(sesionId))
            val porApp = JSONArray()
            for (a in net.topAppsBytes(sesionId, 10)) porApp.put(JSONObject().put("app", a.app).put("bytes", a.n))
            red.put("top_apps_bytes", porApp)
            val destinos = JSONArray()
            for (x in net.topDestinos(sesionId, 10)) destinos.put(JSONObject().put("ip", x.ip).put("enviado", x.salida).put("recibido", x.entrada))
            red.put("top_destinos", destinos)
            stats.put("red", red)
        }
        val tipos = JSONObject()
        for (t in eventos.sysPorTipo(sesionId)) tipos.put(t.tipo, t.n)
        stats.put("eventos_sistema_por_tipo", tipos)
        raiz.put("estadisticas", stats)

        // ---- línea base de la sesión ----
        val base = JSONObject()
        val muestras = analisis.muestrasDeSesion(sesionId)
        val porMetrica = muestras.groupBy { it.metrica }
        val metricas = JSONArray()
        for ((metrica, filas) in porMetrica) {
            var suma = 0.0
            var maximo = 0
            for (f in filas) {
                suma += f.valor
                if (f.valor > maximo) maximo = f.valor
            }
            metricas.put(
                JSONObject().put("metrica", MotorBase.nombreMetrica(metrica)).put("horas_medidas", filas.size)
                    .put("media_por_hora", Math.round(suma / filas.size * 10.0) / 10.0).put("maximo_por_hora", maximo)
            )
        }
        val totalMuestras = analisis.contarMuestras()
        base.put("estado", if (totalMuestras > 0 && analisis.todaBaseline().isNotEmpty()) "con datos" else "aprendiendo")
        base.put("muestras_guardadas", totalMuestras)
        base.put("metricas_de_la_sesion", metricas)
        raiz.put("linea_base", base)

        raiz.put("indicadores", JSONObject().put("cargados", iocs).put("fuentes", analisis.iocsPorFuente().size))
        raiz.put("crudos_incluidos", incluirCrudos)

        if (incluirCrudos) {
            val crudos = JSONObject()
            val conexiones = JSONArray()
            for (e in bd.netDao().todos(sesionId).take(MAX_CRUDOS)) {
                conexiones.put(
                    JSONObject().put("ts", e.ts).put("app", e.app).put("ip", e.ip).put("puerto", e.puerto)
                        .put("proto", e.proto).put("sni", e.sni).put("bytes_in", e.bytesIn).put("bytes_out", e.bytesOut)
                )
            }
            crudos.put("conexiones", conexiones)
            val dns = JSONArray()
            for (e in dnsDao.todos(sesionId).take(MAX_CRUDOS)) {
                dns.put(JSONObject().put("ts", e.ts).put("app", e.app).put("dominio", e.dominio).put("tipo", e.tipo).put("respuesta", e.respuesta))
            }
            crudos.put("dns", dns)
            val sys = JSONArray()
            for (e in eventos.todosSys(sesionId).take(MAX_CRUDOS)) {
                sys.put(JSONObject().put("ts", e.ts).put("tipo", e.tipo).put("datos", e.payload))
            }
            crudos.put("sistema", sys)
            val sen = JSONArray()
            for (e in eventos.todosSensor(sesionId).take(MAX_CRUDOS)) {
                sen.put(JSONObject().put("ts", e.ts).put("app", e.app).put("sensor", e.sensor).put("modo", e.modo))
            }
            crudos.put("sensores", sen)
            val pan = JSONArray()
            for (e in eventos.todosA11y(sesionId).take(MAX_CRUDOS)) {
                pan.put(JSONObject().put("ts", e.ts).put("app", e.app).put("tipo", e.tipoEvento).put("detalle", e.detalle))
            }
            crudos.put("pantalla", pan)
            raiz.put("crudos", crudos)
        }
        raiz.toString()
    }

    /** Genera el informe sin datos crudos, lo firma y lo guarda en la base cifrada. */
    suspend fun generarYGuardar(contexto: Context, sesionId: Long): Boolean {
        val contenido = construir(contexto, sesionId, false) ?: return false
        val sobre = Firma.sobre(contenido)
        withContext(Dispatchers.IO) {
            AppDatabase.obtener(contexto.applicationContext).informesDao()
                .guardarInforme(InformeEntity(sessionId = sesionId, ts = System.currentTimeMillis(), contenido = sobre.toString()))
        }
        return true
    }

    /** Informe guardado (sobre firmado en texto), si existe. */
    suspend fun guardado(contexto: Context, sesionId: Long): String? = withContext(Dispatchers.IO) {
        AppDatabase.obtener(contexto.applicationContext).informesDao().informe(sesionId)?.contenido
    }
}
