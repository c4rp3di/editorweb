package com.carpe.microlisto

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
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

        if (savedInstanceState == null) mostrarFragment(GrabarFragment(), 0)
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