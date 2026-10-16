package com.vigia.app.core

import android.content.Context
import com.vigia.app.alert.AjustesAlertas
import com.vigia.app.informe.InformeGenerador
import com.vigia.app.store.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Datos crudos efímeros: cuando el usuario lo elige, los eventos de una sesión se borran y solo quedan el
 * informe firmado, los hallazgos y las estadísticas de la línea base. Antes de borrar se garantiza que
 * existe el informe.
 */
object Retencion {
    private const val SIETE_DIAS_MS = 7L * 24L * 3_600_000L

    suspend fun aplicarAlCerrar(contexto: Context, sesionId: Long) {
        if (AjustesAlertas(contexto).retencion == "cerrar") borrarCrudos(contexto, sesionId)
    }

    suspend fun aplicarPorEdad(contexto: Context) {
        if (AjustesAlertas(contexto).retencion != "siete_dias") return
        val bd = withContext(Dispatchers.IO) { AppDatabase.obtener(contexto.applicationContext) }
        val viejas = withContext(Dispatchers.IO) { bd.sessionDao().cerradasAntesDe(System.currentTimeMillis() - SIETE_DIAS_MS) }
        for (s in viejas) {
            if (SessionManager.crudosBorradosDe(s.configuracion) == 0L) borrarCrudos(contexto, s.id)
        }
    }

    /** Borra los datos crudos de una sesión cerrada. Devuelve false si no se pudo (por ejemplo, sin informe). */
    suspend fun borrarCrudos(contexto: Context, sesionId: Long): Boolean {
        val app = contexto.applicationContext
        val bd = withContext(Dispatchers.IO) { AppDatabase.obtener(app) }
        val sesion = withContext(Dispatchers.IO) { bd.sessionDao().obtener(sesionId) } ?: return false
        if (sesion.fin == null) return false

        val hayInforme = withContext(Dispatchers.IO) { bd.informesDao().informe(sesionId) } != null
        if (!hayInforme && !InformeGenerador.generarYGuardar(app, sesionId)) return false

        withContext(Dispatchers.IO) {
            bd.dnsDao().borrarDeSesion(sesionId)
            val ev = bd.eventosDao()
            ev.borrarSys(sesionId)
            ev.borrarSensor(sesionId)
            ev.borrarA11y(sesionId)
            bd.inventarioDao().borrar(sesionId)
            val config = try {
                JSONObject(sesion.configuracion)
            } catch (e: Exception) {
                JSONObject()
            }
            config.put("crudos_borrados", System.currentTimeMillis())
            bd.sessionDao().actualizar(sesion.copy(configuracion = config.toString()))
        }
        return true
    }
}
