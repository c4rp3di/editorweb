package com.carpe.microlisto

import android.content.Context

object AppLogic {

    fun onIniciar(context: Context) {
        // Punto de entrada para inicializaciones globales.
        // Aquí irán los managers de audio, transcripción y base de datos
        // cuando los implementemos en el Bloque 2.
    }

    fun debeMostrarConfiguracion(context: Context): Boolean {
        return ConfiguracionInicialActivity.debeMostrarConfiguracion(context)
    }
}