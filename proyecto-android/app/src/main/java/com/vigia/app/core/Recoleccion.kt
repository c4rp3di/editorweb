package com.vigia.app.core

import com.vigia.app.collect.EscritorEventos

/**
 * Estado compartido entre los módulos de recolección y el servicio de accesibilidad
 * (que el sistema crea por su cuenta). Fuera de una sesión activa nada se registra.
 */
object Recoleccion {
    /** Destino de los eventos de la sesión en curso (null si no hay sesión o ningún módulo). */
    @Volatile
    var sink: EscritorEventos? = null

    /** true solo mientras la sesión está ACTIVA (no en pausa ni cerrada). */
    @Volatile
    var grabando: Boolean = false

    /** Paquete de la app en pantalla, si el módulo de accesibilidad está funcionando. */
    @Volatile
    var primerPlano: String? = null
}
