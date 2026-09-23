package com.carpe.microlisto

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.carpe.microlisto.transcripcion.VoskManager
import com.carpe.microlisto.ui.AjustesFragment
import com.carpe.microlisto.ui.DetalleFragment
import com.carpe.microlisto.ui.GrabarFragment
import com.carpe.microlisto.ui.HistorialFragment

class MainActivity : AppCompatActivity() {

    private lateinit var botones: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppLogic.onIniciar(this)

        botones = listOf(
            findViewById(R.id.nav_grabar),
            findViewById(R.id.nav_historial),
            findViewById(R.id.nav_detalle),
            findViewById(R.id.nav_ajustes)
        )

        findViewById<Button>(R.id.nav_grabar).setOnClickListener { mostrarFragment(GrabarFragment(), 0) }
        findViewById<Button>(R.id.nav_historial).setOnClickListener { mostrarFragment(HistorialFragment(), 1) }
        findViewById<Button>(R.id.nav_detalle).setOnClickListener { mostrarFragment(DetalleFragment(), 2) }
        findViewById<Button>(R.id.nav_ajustes).setOnClickListener { mostrarFragment(AjustesFragment(), 3) }

        if (savedInstanceState == null) {
            mostrarFragment(GrabarFragment(), 0)
            comprobarModeloVosk()
        }
    }

    /**
     * Si el modelo Vosk no está descargado, ofrece descargarlo.
     * Se muestra una sola vez por sesión, tras el primer arranque.
     */
    private fun comprobarModeloVosk() {
        val prefs = getSharedPreferences("microlisto_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("dialogo_vosk_mostrado", false)) return

        val vm = VoskManager(applicationContext)
        if (vm.estaDescargado()) return

        prefs.edit().putBoolean("dialogo_vosk_mostrado", true).apply()

        AlertDialog.Builder(this)
            .setTitle("Modelo de transcripción")
            .setMessage("Para transcribir conversaciones necesitas descargar el modelo de voz (~1.4 GB).\n\n" +
                    "Puedes hacerlo ahora desde Ajustes, o grabar sin transcripción y descargarlo más tarde.\n\n" +
                    "Sin modelo, la app seguirá grabando y guardando el audio, y podrás reprocesarlo cuando lo tengas.")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                mostrarFragment(AjustesFragment(), 3)
            }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    fun mostrarFragment(fragment: Fragment, indiceBoton: Int) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedor_fragments, fragment)
            .commit()
        botones.forEachIndexed { i, b ->
            b.setTextColor(if (i == indiceBoton) 0xFFF0EEF8.toInt() else 0xFFB0B0D0.toInt())
        }
    }

    fun abrirDetalle(idConversacion: Long) {
        mostrarFragment(DetalleFragment.nuevo(idConversacion), 2)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val frag = supportFragmentManager.findFragmentById(R.id.contenedor_fragments)
        when (frag) {
            is DetalleFragment -> mostrarFragment(HistorialFragment(), 1)
            is HistorialFragment, is AjustesFragment -> mostrarFragment(GrabarFragment(), 0)
            else -> super.onBackPressed()
        }
    }
}