package com.carpe.gestorarchivos.ui

import android.content.Intent
import com.carpe.gestorarchivos.data.SolicitudExterna

/** Lo que implementa la actividad que aloja a [PantallaGestor] cuando otra app le hace una petición. */
interface AnfitrionExterno {
    val solicitud: SolicitudExterna

    /** Devuelve [resultado] a la app que llamó y cierra. */
    fun terminarConResultado(resultado: Intent)

    /** Cierra sin resultado (la app que llamó recibe «cancelado»). */
    fun cancelar()

    /** Cierra tras completar una acción que no devuelve datos (guardar lo recibido). */
    fun terminar()
}
