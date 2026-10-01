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
import android.os.Bundleimport android.os.PersistableBundle
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

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState, persistentState)

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

        vistaContador = crearVistaContador()
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

    private fun crearVistaContador(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )

            val tvTitulo = TextView(context).apply {
                text = "Pasos de Hoy"
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 32)
            }

            tvPasos = TextView(context).apply {
                text = "0"
                textSize = 64f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 48)
            }

            val btnIniciar = Button(context).apply {
                text = "Iniciar Monitoreo"
                setOnClickListener { solicitarPermisosEIniciar() }
            }

            val btnDetener = Button(context).apply {
                text = "Detener Servicio"
                setOnClickListener { detenerServicioPasos() }
            }

            val btnReiniciar = Button(context).apply {
                text = "Reiniciar Pasos de Hoy"
                setOnClickListener { servicioPasos?.reiniciarContador() }
            }

            addView(tvTitulo)
            addView(tvPasos)
            addView(btnIniciar)
            addView(btnDetener)
            addView(btnReiniciar)
        }
    }

    private fun crearVistaEstadisticas(): ScrollView {
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)

            val tvTituloEst = TextView(context).apply {
                text = "Resumen de Actividad"
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 32)
            }

            tvKm = TextView(context).apply {
                text = "Distancia: 0.00 km"
                textSize = 18f
                setPadding(0, 16, 0, 16)
            }

            tvKcal = TextView(context).apply {
                text = "Calorías: 0 kcal"
                textSize = 18f
                setPadding(0, 16, 0, 16)
            }

            tvMinutos = TextView(context).apply {
                text = "Tiempo activo: 0 min"
                textSize = 18f
                setPadding(0, 16, 0, 32)
            }

            val tvTituloHistorial = TextView(context).apply {
                text = "Historial (Últimos 7 días)"
                textSize = 20f
                setPadding(0, 32, 0, 16)
            }

            contenedorHistorial = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            addView(tvTituloEst)
            addView(tvKm)
            addView(tvKcal)
            addView(tvMinutos)
            addView(tvTituloHistorial)
            addView(contenedorHistorial)
        }

        scroll.addView(layout)
        return scroll
    }

    private fun crearBarraNavegacion(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            btnTabContador = Button(context).apply {
                text = "Contador"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { mostrarPestana(true) }
            }

            btnTabEstadisticas = Button(context).apply {
                text = "Estadísticas"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { mostrarPestana(false) }
            }

            addView(btnTabContador)
            addView(btnTabEstadisticas)
        }
    }

    private fun mostrarPestana(esContador: Boolean) {
        if (esContador) {
            vistaContador.visibility = View.VISIBLE
            vistaEstadisticas.visibility = View.GONE
        } else {
            vistaContador.visibility = View.GONE
            vistaEstadisticas.visibility = View.VISIBLE
            cargarHistorial()
        }
    }

    private fun actualizarMetricas(pasos: Int) {
        tvPasos.text = pasos.toString()

        val km = (pasos * 0.0007)
        val kcal = (pasos * 0.04).toInt()
        val minutos = pasos / 100

        tvKm.text = String.format(Locale.getDefault(), "Distancia: %.2f km", km)
        tvKcal.text = "Calorías: $kcal kcal"
        tvMinutos.text = "Tiempo activo: ~$minutos min"
    }

    private fun cargarHistorial() {
        contenedorHistorial.removeAllViews()
        val prefs = getSharedPreferences("pasos_prefs", Context.MODE_PRIVATE)
        val sdfKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sdfMostrar = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        val cal = Calendar.getInstance()
        for (i in 0..6) {
            val fechaKey = sdfKey.format(cal.time)
            val fechaTexto = if (i == 0) "Hoy" else sdfMostrar.format(cal.time)
            val pasosDia = prefs.getInt("pasos_$fechaKey", 0)

            val tvItem = TextView(this).apply {
                text = "$fechaTexto: $pasosDia pasos"
                textSize = 16f
                setPadding(16, 12, 16, 12)
            }
            contenedorHistorial.addView(tvItem)

            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
    }

    private fun solicitarPermisosEIniciar() {
        val permisosAObtener = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                permisosAObtener.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permisosAObtener.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permisosAObtener.isNotEmpty()) {
            requestPermissions(permisosAObtener.toTypedArray(), 101)
        } else {
            iniciarServicioPasos()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val todosConcedidos = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (todosConcedidos) {
                iniciarServicioPasos()
            } else {
                Toast.makeText(this, "Se requieren permisos para contar pasos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun iniciarServicioPasos() {
        val intent = Intent(this, ServicioPasos::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        bindService(intent, conexionServicio, Context.BIND_AUTO_CREATE)
        Toast.makeText(this, "Servicio iniciado", Toast.LENGTH_SHORT).show()
    }

    private fun detenerServicioPasos() {
        if (estaVinculado) {
            unbindService(conexionServicio)
            estaVinculado = false
        }
        val intent = Intent(this, ServicioPasos::class.java)
        stopService(intent)
        Toast.makeText(this, "Servicio detenido", Toast.LENGTH_SHORT).show()
    }

    private fun comprobarEstadoInicial() {
        val prefs = getSharedPreferences("pasos_prefs", Context.MODE_PRIVATE)
        val sdfKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val hoy = sdfKey.format(Date())
        val pasosGuardados = prefs.getInt("pasos_$hoy", 0)
        actualizarMetricas(pasosGuardados)

        val intent = Intent(this, ServicioPasos::class.java)
        bindService(intent, conexionServicio, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (estaVinculado) {
            unbindService(conexionServicio)
            estaVinculado = false
        }
    }
}