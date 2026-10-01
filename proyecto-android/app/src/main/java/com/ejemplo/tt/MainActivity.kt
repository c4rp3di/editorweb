package com.ejemplo.tt\n\nimport com.ejemplo.tt.ServicioPasos

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.graphics.Color
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private var servicioPasos: ServicioPasos? = null
    private var estaVinculado = false

    private lateinit var contenedorPrincipal: LinearLayout
    private lateinit var vistaContador: LinearLayout
    private lateinit var vistaEstadisticas: ScrollView

    private lateinit var tvPasos: TextView
    private lateinit var tvKm: TextView
    private lateinit var tvKcal: TextView
    private lateinit var tvMinutos: TextView
    private lateinit var contenedorHistorial: LinearLayout

    private lateinit var btnTabContador: Button
    private lateinit var btnTabEstadisticas: Button\n\nprivate fun actualizarMetricas(pasos: Int) {\n\nprivate fun crearVistaContador(): LinearLayout {
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Configurar la vista del contador
    }
}
    tvPasos.text = pasos.toString()
    // Actualizar otras métricas si es necesario
}

    private val conexionServicio = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ServicioPasos.LocalBinder
            servicioPasos = binder.getService()
            estaVinculado = true

            servicioPasos?.registrarCallback { pasos ->
                runOnUiThread {
                    actualizarMetricas(pasos)
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            estaVinculado = false
            servicioPasos = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        contenedorPrincipal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val contenidoFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        vistaContador = crearVistaContador()\n        tvPasos = TextView(this)\n        tvKm = TextView(this)\n        tvKcal = TextView(this)\n        tvMinutos = TextView(this)\n        contenedorHistorial = LinearLayout(this)\n        btnTabContador = Button(this)\n        btnTabEstadisticas = Button(this)
        vistaEstadisticas = crearVistaEstadisticas()

        contenidoFrame.addView(vistaContador)
        contenidoFrame.addView(vistaEstadisticas)
        vistaEstadisticas.visibility = View.GONE

        val barraNavegacion = crearBarraNavegacion()

        contenedorPrincipal.addView(contenidoFrame)
        contenedorPrincipal.addView(barraNavegacion)

        setContentView(contenedorPrincipal)

        comprobarEstadoInicial()
    }

    // Resto del código...
}