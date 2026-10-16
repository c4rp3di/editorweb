package com.vigia.app.collect

import android.content.Context
import com.vigia.app.collect.events.ModuloEventos
import com.vigia.app.collect.inventory.ModuloInventario
import com.vigia.app.collect.sensors.ModuloSensores
import com.vigia.app.core.Estado
import com.vigia.app.core.Modulos
import com.vigia.app.core.Recoleccion
import com.vigia.app.core.SesionUi
import kotlinx.coroutines.CoroutineScope

/**
 * Arranca y para los módulos de recolección según la sesión. Vive dentro del servicio en
 * primer plano: cuando la sesión termina, todo se da de baja y se vacían las colas.
 */
class GestorModulos(private val ctx: Context, private val scope: CoroutineScope) {

    private var sesion = -1L
    private var sink: EscritorEventos? = null
    private var eventos: ModuloEventos? = null
    private var sensores: ModuloSensores? = null
    private var inventario: ModuloInventario? = null

    fun sincronizar(ui: SesionUi) {
        val id = ui.sesionId
        if (ui.estado == Estado.INACTIVA || id == null) return
        if (id != sesion) {
            liberar()
            arrancar(id, ui.modulos)
        }
        Recoleccion.grabando = ui.estado == Estado.ACTIVA
    }

    private fun arrancar(id: Long, m: Modulos) {
        sesion = id
        if (!m.alguno()) return
        val destino = EscritorEventos(ctx, id)
        destino.iniciar(scope)
        sink = destino
        Recoleccion.sink = destino
        if (m.eventos) {
            val e = ModuloEventos(ctx, destino)
            e.iniciar()
            eventos = e
        }
        if (m.sensores) {
            val s = ModuloSensores(ctx, destino)
            s.iniciar()
            sensores = s
        }
        if (m.inventario) {
            val i = ModuloInventario(ctx, destino, id)
            i.iniciar(scope)
            inventario = i
        }
    }

    /** Fin de sesión: para los módulos, toma la foto final del inventario y guarda lo pendiente. */
    suspend fun detener() {
        Recoleccion.grabando = false
        eventos?.detener()
        sensores?.detener()
        inventario?.detener()
        val destino = sink
        Recoleccion.sink = null
        sink = null
        eventos = null
        sensores = null
        inventario = null
        sesion = -1L
        destino?.cerrar()
    }

    /** Baja inmediata, sin guardar lo pendiente (el servicio se destruye). */
    fun liberar() {
        Recoleccion.grabando = false
        eventos?.detener()
        sensores?.detener()
        inventario?.cancelar()
        Recoleccion.sink = null
        sink?.cancelar()
        sink = null
        eventos = null
        sensores = null
        inventario = null
        sesion = -1L
    }
}
