package com.vigia.app.core

import android.content.Context
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.SessionDao
import com.vigia.app.store.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Gestor de sesiones. Fase 1: solo lleva el ciclo de vida (nueva, pausa, detener) y lo guarda
 * en la base cifrada. Todavía no hay módulos de recolección.
 */
class SessionManager private constructor(private val app: Context) {

    private val _estado = MutableStateFlow(SesionUi())
    val estado: StateFlow<SesionUi> = _estado.asStateFlow()

    private suspend fun dao(): SessionDao = withContext(Dispatchers.IO) {
        AppDatabase.obtener(app).sessionDao()
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
                ultimaInicio = ultima?.inicio,
                ultimaFin = ultima?.fin
            )
        } catch (e: Exception) {
            _estado.value = _estado.value.copy(cargado = true, error = describir(e))
        }
    }

    suspend fun iniciar() {
        cargarSiHaceFalta()
        val actual = _estado.value
        if (actual.estado != Estado.INACTIVA) return
        try {
            val ahora = System.currentTimeMillis()
            val id = dao().insertar(SessionEntity(inicio = ahora, estado = ESTADO_ACTIVA))
            _estado.value = actual.copy(
                estado = Estado.ACTIVA,
                sesionId = id,
                inicio = ahora,
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

    private fun describir(e: Exception): String {
        return (e.javaClass.simpleName + ": " + (e.message ?: "sin detalle"))
    }

    companion object {
        const val ESTADO_ACTIVA = "activa"
        const val ESTADO_PAUSADA = "pausada"
        const val ESTADO_CERRADA = "cerrada"

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
