package com.carpe.gestorarchivos.data

import android.content.Context

class Ajustes(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gestor_prefs", Context.MODE_PRIVATE)

    var orden: String
        get() = prefs.getString("orden", "nombre") ?: "nombre"
        set(v) { prefs.edit().putString("orden", v).apply() }

    var ordenAscendente: Boolean
        get() = prefs.getBoolean("orden_asc", true)
        set(v) { prefs.edit().putBoolean("orden_asc", v).apply() }

    var mostrarOcultos: Boolean
        get() = prefs.getBoolean("mostrar_ocultos", false)
        set(v) { prefs.edit().putBoolean("mostrar_ocultos", v).apply() }

    var numerosDeLinea: Boolean
        get() = prefs.getBoolean("numeros_linea", false)
        set(v) { prefs.edit().putBoolean("numeros_linea", v).apply() }
}
