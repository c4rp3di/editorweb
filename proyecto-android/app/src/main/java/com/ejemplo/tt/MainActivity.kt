package com.ejemplo.tt

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.IBinder
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
    private lateinit var btnTabEstadisticas: Button

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

        vistaContador = crearVistaContador()\ntvPasos = TextView(this)\ntvKm = TextView(this)\ntvKcal = TextView(this)\ntvMinutos = TextView(this)\ncontenedorHistorial = LinearLayout(this)\nbtnTabContador = Button(this)\nbtnTabEstadisticas = Button(this)
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