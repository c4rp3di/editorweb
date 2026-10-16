package com.vigia.app.analyze

import android.content.Context
import com.vigia.app.analyze.base.MotorBase
import com.vigia.app.analyze.corr.Correlacion
import com.vigia.app.analyze.heur.MotorHeuristicas
import com.vigia.app.analyze.sig.IndiceIoc
import com.vigia.app.analyze.sig.MotorFirmas
import com.vigia.app.core.SessionManager
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.DominioVistoEntity
import com.vigia.app.store.FindingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orquestador del análisis: firmas → línea base → heurísticas → correlación → hallazgos guardados.
 * Se puede repetir sobre la misma sesión sin duplicar resultados.
 */
object Analizador {

    /** Devuelve cuántos hallazgos (sin contar notas informativas) se generaron. */
    suspend fun ejecutar(contexto: Context, sesionId: Long): Int = withContext(Dispatchers.IO) {
        val app = contexto.applicationContext
        val bd = AppDatabase.obtener(app)
        val analisis = bd.analisisDao()
        val sesion = bd.sessionDao().obtener(sesionId) ?: return@withContext 0
        val ajustes = AjustesAnalisis(app)

        // Si ya se borraron los datos crudos no hay nada que analizar: se conservan los hallazgos guardados.
        if (SessionManager.crudosBorradosDe(sesion.configuracion) > 0L) return@withContext 0

        val modulos = SessionManager.modulosDe(sesion.configuracion)
        val vpn = SessionManager.vpnDe(sesion.configuracion)
        val cerrada = sesion.fin != null
        val fin = sesion.fin ?: System.currentTimeMillis()

        val dns = bd.dnsDao().todos(sesionId)
        val eventos = bd.eventosDao()
        val inv = bd.inventarioDao()
        val pm = app.packageManager

        val datos = DatosSesion(
            sesion = sesion,
            modulos = modulos,
            vpn = vpn,
            inicio = sesion.inicio,
            fin = fin,
            dns = dns,
            sys = eventos.todosSys(sesionId),
            sensores = eventos.todosSensor(sesionId),
            pantalla = eventos.todosA11y(sesionId),
            primerInventario = leer(inv.primero(sesionId)?.payload),
            ultimoInventario = leer(inv.ultimo(sesionId)?.payload),
            dominiosVistos = HashSet(analisis.dominiosVistos()),
            ajustes = ajustes,
            paquetePropio = app.packageName,
            sinLanzador = { paquete ->
                try {
                    pm.getLaunchIntentForPackage(paquete) == null
                } catch (e: Exception) {
                    false
                }
            }
        )

        val hallazgos = ArrayList<Hallazgo>()

        // 1) firmas (indicadores de compromiso)
        val indice = IndiceIoc(analisis.todosIocs())
        if (ajustes.activa("SIG")) {
            if (indice.vacio) {
                hallazgos.add(
                    Hallazgo(
                        0L, Severidad.INFO, "sig", "NOTA", "", "Sin indicadores cargados",
                        "No hay listas de indicadores cargadas, así que no se pudo comprobar nada contra ellas. " +
                            "Cárgalas en Ajustes › Indicadores y vuelve a analizar.",
                        emptyList(), 100
                    )
                )
            } else {
                hallazgos.addAll(MotorFirmas.analizar(datos, indice))
            }
        }

        // 2) línea base
        var muestras = emptyList<com.vigia.app.store.BaselineSampleEntity>()
        if (ajustes.activa("BASE")) {
            val (lista, nuevas) = MotorBase.analizar(analisis, datos, ajustes)
            hallazgos.addAll(lista)
            muestras = nuevas
        }

        // 3) heurísticas
        hallazgos.addAll(MotorHeuristicas.analizar(datos, indice))

        // 4) correlación
        if (ajustes.activa("CORR")) {
            hallazgos.addAll(Correlacion.analizar(datos, hallazgos))
        }

        // Guardado (sustituye los resultados anteriores de la sesión)
        val ahora = System.currentTimeMillis()
        val filas = hallazgos.map { h ->
            FindingEntity(
                sessionId = sesionId,
                ts = if (h.ts > 0L) h.ts else ahora,
                severidad = h.severidad,
                modulo = h.modulo,
                regla = h.regla,
                app = h.app,
                titulo = h.titulo,
                descripcion = h.descripcion,
                evidenciaIds = JSONArray(h.evidencias).toString(),
                confianza = h.confianza
            )
        }
        analisis.borrarFindings(sesionId)
        var i = 0
        while (i < filas.size) {
            analisis.insertarFindings(filas.subList(i, minOf(i + 300, filas.size)))
            i += 300
        }

        // Aprendizaje: solo cuando la sesión está cerrada, para no medir horas a medias.
        if (cerrada) {
            if (ajustes.activa("BASE")) {
                MotorBase.guardar(analisis, sesionId, muestras, MotorBase.diaLocal(fin))
            }
            if (vpn) {
                val nuevos = dns.map { it.dominio }.distinct().map { DominioVistoEntity(it, sesion.inicio) }
                var j = 0
                while (j < nuevos.size) {
                    analisis.insertarDominiosVistos(nuevos.subList(j, minOf(j + 500, nuevos.size)))
                    j += 500
                }
            }
        }
        hallazgos.count { it.severidad != Severidad.INFO }
    }

    private fun leer(texto: String?): JSONObject? {
        if (texto == null) return null
        return try {
            JSONObject(texto)
        } catch (e: Exception) {
            null
        }
    }
}
