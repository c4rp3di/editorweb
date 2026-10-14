package com.vigia.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.vigia.app.collect.vpn.VigiaVpnService
import com.vigia.app.collect.vpn.VpnEstado
import com.vigia.app.core.Estado
import com.vigia.app.core.SesionService
import com.vigia.app.core.SesionUi
import com.vigia.app.core.SessionManager
import com.vigia.app.ui.SesionesAdapter
import com.vigia.app.util.Formato
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var gestor: SessionManager
    private lateinit var drawer: DrawerLayout
    private lateinit var listaSesiones: ListView
    private lateinit var adaptador: SesionesAdapter
    private lateinit var punto: View
    private lateinit var txtEstado: TextView
    private lateinit var txtTiempo: TextView
    private lateinit var txtVpn: TextView
    private lateinit var txtContadores: TextView
    private lateinit var txtError: TextView
    private lateinit var txtUltima: TextView
    private lateinit var chkVpn: MaterialCheckBox
    private lateinit var spDns: Spinner
    private lateinit var btnNueva: MaterialButton
    private lateinit var btnPausa: MaterialButton
    private lateinit var btnDetener: MaterialButton

    private var ui: SesionUi = SesionUi()
    private var servicioIniciado = false
    private var vpnIniciada = false
    private val valoresDns = arrayOf("sistema", "1.1.1.1", "8.8.8.8", "9.9.9.9")

    private val lanzadorVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        val concedido = resultado.resultCode == RESULT_OK
        if (!concedido) {
            Toast.makeText(this, R.string.vpn_denegada, Toast.LENGTH_LONG).show()
        }
        comenzarSesion(concedido)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gestor = SessionManager.obtener(applicationContext)
        drawer = findViewById(R.id.drawer)
        listaSesiones = findViewById(R.id.listaSesiones)
        punto = findViewById(R.id.punto)
        txtEstado = findViewById(R.id.txtEstado)
        txtTiempo = findViewById(R.id.txtTiempo)
        txtVpn = findViewById(R.id.txtVpn)
        txtContadores = findViewById(R.id.txtContadores)
        txtError = findViewById(R.id.txtError)
        txtUltima = findViewById(R.id.txtUltima)
        chkVpn = findViewById(R.id.chkVpn)
        spDns = findViewById(R.id.spDns)
        btnNueva = findViewById(R.id.btnNueva)
        btnPausa = findViewById(R.id.btnPausa)
        btnDetener = findViewById(R.id.btnDetener)
        val btnSesiones = findViewById<MaterialButton>(R.id.btnSesiones)
        val txtVacio = findViewById<TextView>(R.id.txtVacio)

        val adaptadorDns = ArrayAdapter.createFromResource(
            this, R.array.dns_opciones, android.R.layout.simple_spinner_item
        )
        adaptadorDns.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spDns.adapter = adaptadorDns

        adaptador = SesionesAdapter(this)
        listaSesiones.adapter = adaptador
        listaSesiones.emptyView = txtVacio
        listaSesiones.setOnItemClickListener { _, _, posicion, _ ->
            val sesion = adaptador.getItem(posicion)
            if (sesion != null) {
                drawer.closeDrawer(GravityCompat.START)
                val intent = Intent(this, SessionDetailActivity::class.java)
                intent.putExtra(SessionDetailActivity.EXTRA_SESION, sesion.id)
                startActivity(intent)
            }
        }
        btnSesiones.setOnClickListener { drawer.openDrawer(GravityCompat.START) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        btnNueva.setOnClickListener { alPulsarNueva() }
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
                launch { gestor.sesiones().collect { adaptador.actualizar(it) } }
                launch { VpnEstado.activa.collect { renderVpn() } }
                launch { VpnEstado.mensaje.collect { renderVpn() } }
                launch {
                    var vuelta = 0
                    while (true) {
                        actualizarTiempo()
                        if (vuelta % 2 == 0) actualizarContadores()
                        vuelta++
                        delay(1000)
                    }
                }
            }
        }
    }

    private fun alPulsarNueva() {
        pedirPermisoNotificaciones()
        if (chkVpn.isChecked) {
            val intentConsentimiento = VpnService.prepare(this)
            if (intentConsentimiento != null) {
                lanzadorVpn.launch(intentConsentimiento)
            } else {
                comenzarSesion(true)
            }
        } else {
            comenzarSesion(false)
        }
    }

    private fun comenzarSesion(conVpn: Boolean) {
        val posicion = spDns.selectedItemPosition
        val dns = if (posicion >= 0 && posicion < valoresDns.size) valoresDns[posicion] else "sistema"
        lifecycleScope.launch { gestor.iniciar(conVpn, dns) }
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
        chkVpn.isEnabled = !abierta
        spDns.isEnabled = !abierta
        btnPausa.isEnabled = abierta
        btnPausa.setText(if (pausada) R.string.reanudar else R.string.pausar)
        btnDetener.isEnabled = abierta

        val inicioUltima = nuevo.ultimaInicio
        val finUltima = nuevo.ultimaFin
        if (inicioUltima != null && finUltima != null) {
            txtUltima.text = "Última sesión: " + Formato.fecha(inicioUltima) +
                " · duró " + Formato.duracion(finUltima - inicioUltima)
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
        if (abierta && nuevo.vpn && !vpnIniciada) {
            if (VpnService.prepare(this) == null) {
                startService(Intent(this, VigiaVpnService::class.java))
                vpnIniciada = true
            }
        }
        if (!abierta) {
            servicioIniciado = false
            vpnIniciada = false
        }
        renderVpn()
        actualizarTiempo()
    }

    private fun renderVpn() {
        val abierta = ui.estado != Estado.INACTIVA
        if (abierta && ui.vpn) {
            txtVpn.visibility = View.VISIBLE
            val mensaje = VpnEstado.mensaje.value
            if (mensaje != null) {
                txtVpn.text = mensaje
            } else {
                txtVpn.setText(if (VpnEstado.activa.value) R.string.vpn_activa else R.string.vpn_inactiva)
            }
        } else {
            txtVpn.visibility = View.GONE
        }
    }

    private fun actualizarTiempo() {
        val inicio = ui.inicio
        if (inicio != null && ui.estado != Estado.INACTIVA) {
            txtTiempo.text = Formato.duracion(System.currentTimeMillis() - inicio)
        } else {
            txtTiempo.text = "00:00:00"
        }
    }

    private suspend fun actualizarContadores() {
        val id = ui.sesionId
        if (id != null && ui.estado != Estado.INACTIVA && ui.vpn) {
            val c = gestor.contadores(id)
            txtContadores.text = getString(R.string.contadores, c.consultas, c.dominios, c.apps)
            txtContadores.visibility = View.VISIBLE
        } else {
            txtContadores.visibility = View.GONE
        }
    }

    private fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}
