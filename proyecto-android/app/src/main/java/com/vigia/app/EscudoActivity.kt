package com.vigia.app

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.vigia.app.analyze.net.SondaTls
import com.vigia.app.collect.vpn.VpnEstado
import com.vigia.app.core.Estado
import com.vigia.app.core.SessionManager
import com.vigia.app.store.BloqueoEntity
import com.vigia.app.util.Formato
import kotlinx.coroutines.launch

/** El Escudo: lista de dominios e IPs bloqueados mientras hay una sesión con captura activa, y comprobación de la red. */
class EscudoActivity : AppCompatActivity() {

    private lateinit var gestor: SessionManager
    private lateinit var etBloqueo: EditText
    private lateinit var escEstado: TextView
    private lateinit var escVacio: TextView
    private lateinit var btnSonda: MaterialButton
    private lateinit var adaptador: ArrayAdapter<BloqueoEntity>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_escudo)
        gestor = SessionManager.obtener(applicationContext)

        etBloqueo = findViewById(R.id.etBloqueo)
        escEstado = findViewById(R.id.escEstado)
        escVacio = findViewById(R.id.escVacio)
        btnSonda = findViewById(R.id.btnSondaTls)
        val lista = findViewById<ListView>(R.id.listaBloqueos)

        adaptador = object : ArrayAdapter<BloqueoEntity>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val vista = super.getView(position, convertView, parent)
                val b = getItem(position)
                val t1 = vista.findViewById<TextView>(android.R.id.text1)
                val t2 = vista.findViewById<TextView>(android.R.id.text2)
                if (b != null) {
                    t1.text = (if (b.tipo == "ip") "IP " else "Dominio ") + b.valor
                    t2.text = "Añadido el " + Formato.fecha(b.creado) + (if (b.nota.isEmpty()) "" else " · " + b.nota)
                }
                return vista
            }
        }
        lista.adapter = adaptador

        findViewById<MaterialButton>(R.id.btnVolver).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnBloquearDominio).setOnClickListener { anadir("dominio") }
        findViewById<MaterialButton>(R.id.btnBloquearIp).setOnClickListener { anadir("ip") }
        btnSonda.setOnClickListener { comprobarRed() }
        lista.setOnItemClickListener { _, _, posicion, _ ->
            val b = adaptador.getItem(posicion)
            if (b != null) confirmarQuitar(b)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    gestor.bloqueos().collect { filas ->
                        adaptador.setNotifyOnChange(false)
                        adaptador.clear()
                        adaptador.addAll(filas)
                        adaptador.notifyDataSetChanged()
                        escVacio.visibility = if (filas.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    gestor.estado.collect { ui ->
                        val abierta = ui.estado != Estado.INACTIVA
                        escEstado.setText(
                            if (abierta && ui.vpn && ui.red) R.string.escudo_activo_red
                            else if (abierta && ui.vpn) R.string.escudo_activo_dns
                            else R.string.escudo_inactivo
                        )
                    }
                }
                launch { VpnEstado.activa.collect { } }
            }
        }
    }

    private fun anadir(tipo: String) {
        val texto = etBloqueo.text.toString()
        lifecycleScope.launch {
            val ok = gestor.bloquear(tipo, texto, "añadido a mano")
            if (ok) {
                etBloqueo.setText("")
                Toast.makeText(this@EscudoActivity, getString(R.string.bloqueo_anadido, texto.trim()), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@EscudoActivity, R.string.bloqueo_invalido, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmarQuitar(b: BloqueoEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.quitar_bloqueo)
            .setMessage(b.valor)
            .setPositiveButton(R.string.quitar_bloqueo) { _, _ ->
                lifecycleScope.launch { gestor.desbloquear(b.id) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun comprobarRed() {
        btnSonda.isEnabled = false
        btnSonda.setText(R.string.comprobando)
        lifecycleScope.launch {
            val informe = SondaTls.comprobar(applicationContext)
            val ui = gestor.estado.value
            val id = ui.sesionId
            if (id != null && ui.estado != Estado.INACTIVA && informe.hallazgos.isNotEmpty()) {
                SondaTls.guardarHallazgos(applicationContext, id, informe)
            }
            btnSonda.isEnabled = true
            btnSonda.setText(R.string.comprobar_red)
            AlertDialog.Builder(this@EscudoActivity)
                .setTitle(R.string.comprobar_red)
                .setMessage(informe.texto)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
