package com.vigia.app.collect

import android.content.Context
import com.vigia.app.store.A11yEventEntity
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.SensorEventEntity
import com.vigia.app.store.SysEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Destino común de los módulos de recolección. Los eventos se encolan (sin bloquear a quien
 * los produce) y se guardan por lotes en la base cifrada, cada uno en su tabla.
 */
class EscritorEventos(private val app: Context, val sesionId: Long) {

    private val cola = Channel<Any>(Channel.UNLIMITED)
    private var job: Job? = null

    fun iniciar(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) { bucle() }
    }

    fun sys(tipo: String, payload: String) {
        cola.trySend(SysEventEntity(sessionId = sesionId, ts = System.currentTimeMillis(), tipo = tipo, payload = payload))
    }

    fun sensor(uid: Int, aplicacion: String, sensor: String, modo: String) {
        cola.trySend(
            SensorEventEntity(
                sessionId = sesionId, ts = System.currentTimeMillis(), uid = uid,
                app = aplicacion, sensor = sensor, modo = modo
            )
        )
    }

    fun a11y(uid: Int, aplicacion: String, tipoEvento: String, detalle: String) {
        cola.trySend(
            A11yEventEntity(
                sessionId = sesionId, ts = System.currentTimeMillis(), uid = uid,
                app = aplicacion, tipoEvento = tipoEvento, detalle = detalle
            )
        )
    }

    /** Cierra la cola y espera a que se guarde todo lo pendiente. */
    suspend fun cerrar() {
        cola.close()
        job?.join()
    }

    /** Cierra sin esperar (solo si el servicio se destruye de golpe). */
    fun cancelar() {
        cola.close()
        job?.cancel()
    }

    private suspend fun bucle() {
        val dao = AppDatabase.obtener(app).eventosDao()
        while (true) {
            val primero = cola.receiveCatching().getOrNull() ?: break
            val sys = ArrayList<SysEventEntity>()
            val sensores = ArrayList<SensorEventEntity>()
            val pantalla = ArrayList<A11yEventEntity>()
            clasificar(primero, sys, sensores, pantalla)
            var n = 0
            while (n < 200) {
                val otro = cola.tryReceive().getOrNull() ?: break
                clasificar(otro, sys, sensores, pantalla)
                n++
            }
            try {
                if (sys.isNotEmpty()) dao.insertarSys(sys)
                if (sensores.isNotEmpty()) dao.insertarSensor(sensores)
                if (pantalla.isNotEmpty()) dao.insertarA11y(pantalla)
            } catch (e: Exception) {
            }
        }
    }

    private fun clasificar(
        x: Any,
        sys: MutableList<SysEventEntity>,
        sensores: MutableList<SensorEventEntity>,
        pantalla: MutableList<A11yEventEntity>
    ) {
        when (x) {
            is SysEventEntity -> sys.add(x)
            is SensorEventEntity -> sensores.add(x)
            is A11yEventEntity -> pantalla.add(x)
        }
    }
}
