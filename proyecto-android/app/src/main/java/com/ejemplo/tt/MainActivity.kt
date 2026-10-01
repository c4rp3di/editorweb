package com.ejemplo.tt

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Activity

class MainActivity : Activity() {

    private var servicioPasos: ServicioPasos? = null
    private var estaVinculado = false

    private lateinit var tvPasos: TextView
    private lateinit var btnIniciar: Button
    private lateinit var btnDetener: Button
    private lateinit var btnReiniciar: Button

    private val conexionServicio = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ServicioPasos.LocalBinder
            servicioPasos = binder.getService()
            estaVinculado = true

            servicioPasos?.registrarCallback { pasos ->
                runOnUiThread {
                    tvPasos.text = pasos.toString()
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            estaVinculado = false
            servicioPasos = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val tvTitulo = TextView(this).apply {
            text = "Contador de Pasos"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        tvPasos = TextView(this).apply {
            text = "0"
            textSize = 64f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        btnIniciar = Button(this).apply {
            text = "Iniciar Monitoreo"
            setOnClickListener { solicitarPermisosEIniciar() }
        }

        btnDetener = Button(this).apply {
            text = "Detener Servicio"
            setOnClickListener { detenerServicioPasos() }
        }

        btnReiniciar = Button(this).apply {
            text = "Reiniciar Pasos"
            setOnClickListener {
                servicioPasos?.reiniciarContador()
            }
        }

        layout.addView(tvTitulo)
        layout.addView(tvPasos)
        layout.addView(btnIniciar)
        layout.addView(btnDetener)
        layout.addView(btnReiniciar)

        setContentView(layout)

        comprobarEstadoInicial()
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
        val pasosGuardados = prefs.getInt("pasos_totales", 0)
        tvPasos.text = pasosGuardados.toString()

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