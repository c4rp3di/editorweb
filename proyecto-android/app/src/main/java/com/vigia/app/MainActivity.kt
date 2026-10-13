package com.vigia.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.vigia.app.core.Estado
import com.vigia.app.core.SesionService
import com.vigia.app.core.SesionUi
import com.vigia.app.core.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var gestor: SessionManager
    private lateinit var punto: View
    private lateinit var txtEstado: TextView
    private lateinit var txtTiempo: TextView
    private lateinit var txtError: TextView
    private lateinit var txtUltima: TextView
    private lateinit var btnNueva: MaterialButton
    private lateinit var btnPausa: MaterialButton
    private lateinit var btnDetener: MaterialButton

    private var ui: SesionUi = SesionUi()
    private var servicioIniciado = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gestor = SessionManager.obtener(applicationContext)
        punto = findViewById(R.id.punto)
        txtEstado = findViewById(R.id.txtEstado)
        txtTiempo = findViewById(R.id.txtTiempo)
        txtError = findViewById(R.id.txtError)
        txtUltima = findViewById(R.id.txtUltima)
        btnNueva = findViewById(R.id.btnNueva)
        btnPausa = findViewById(R.id.btnPausa)
        btnDetener = findViewById(R.id.btnDetener)

        btnNueva.setOnClickListener {
            pedirPermisoNotificaciones()
            lifecycleScope.launch { gestor.iniciar() }
        }
        btnPausa.setOnClickListener {
            lifecycleScope.launch { gestor.alternarPausa() }
        }
        btnDetener.setOnClickListener {
            lifecycleScope.launch { gestor.detener() }
        }

        render(ui)
        lifecycleScope.launch { gestor.cargarSiHaceFalta() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { gestor.estado.collect { render(it) } }
                launch {
                    while (true) {
                        actualizarTiempo()
                        delay(1000)
                    }
                }
            }
        }
    }

    private fun render(nuevo: SesionUi) {
        ui = nuevo
        val activa = nuevo.estado == Estado.ACTIVA
        val pausada = nuevo.estado == Estado.PAUSADA
        val abierta = activa || pausada

        val colorId = if (activa) R.color.vigia_rojo else if (pausada) R.color.vigia_ambar else R.color.vigia_gris
        val fondo = punto.background.mutate()
        if (fondo is GradientDrawable) {
            fondo.setColor(ContextCompat.getColor(this, colorId))
        }

        val textoEstado = if (activa) R.string.estado_activa else if (pausada) R.string.estado_pausada else R.string.estado_inactiva
        txtEstado.setText(textoEstado)

        btnNueva.isEnabled = nuevo.cargado && !abierta
        btnPausa.isEnabled = abierta
        btnPausa.setText(if (pausada) R.string.reanudar else R.string.pausar)
        btnDetener.isEnabled = abierta

        val inicioUltima = nuevo.ultimaInicio
        val finUltima = nuevo.ultimaFin
        if (inicioUltima != null && finUltima != null) {
            val fecha = DateFormat.format("d MMM yyyy HH:mm", Date(inicioUltima))
            txtUltima.text = "Última sesión: " + fecha + " · duró " + formato(finUltima - inicioUltima)
        } else {
            txtUltima.setText(R.string.sin_sesiones)
        }

        val error = nuevo.error
        if (error != null) {
            txtError.text = "Error de almacén: " + error
            txtError.visibility = View.VISIBLE
        } else {
            txtError.visibility = View.GONE
        }

        if (abierta && !servicioIniciado) {
            SesionService.iniciar(this)
            servicioIniciado = true
        }
        if (!abierta) {
            servicioIniciado = false
        }
        actualizarTiempo()
    }

    private fun actualizarTiempo() {
        val inicio = ui.inicio
        if (inicio != null && ui.estado != Estado.INACTIVA) {
            txtTiempo.text = formato(System.currentTimeMillis() - inicio)
        } else {
            txtTiempo.text = "00:00:00"
        }
    }

    private fun formato(ms: Long): String {
        val s = if (ms < 0) 0L else ms / 1000
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
