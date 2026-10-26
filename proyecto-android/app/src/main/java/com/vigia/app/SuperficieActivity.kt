package com.vigia.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.app.integridad.Higiene
import com.vigia.app.integridad.Punto
import com.vigia.app.integridad.Superficie
import com.vigia.app.util.Ayuda
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Modo de reducción de superficie: lo que ahora mismo amplía la exposición del teléfono y dónde cambiarlo. */
class SuperficieActivity : AppCompatActivity() {

    private lateinit var contenedor: LinearLayout
    private lateinit var resumen: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_superficie)
        Ayuda.instalar(this, R.string.ayuda_superficie_titulo, R.string.ayuda_superficie)
        contenedor = findViewById(R.id.contenedorSuperficie)
        resumen = findViewById(R.id.supResumen)
        findViewById<MaterialButton>(R.id.btnVolver).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        resumen.setText(R.string.revisando)
        lifecycleScope.launch {
            val puntos = withContext(Dispatchers.IO) { Superficie.revisar(applicationContext) }
            mostrar(puntos)
            Higiene.marcarRevision(applicationContext)
        }
    }

    private fun mostrar(puntos: List<Punto>) {
        contenedor.removeAllViews()
        val pendientes = puntos.count { it.nivel != Punto.OK }
        resumen.text = if (pendientes == 0) {
            getString(R.string.superficie_todo_bien)
        } else {
            getString(R.string.superficie_pendientes, pendientes, puntos.size)
        }
        val margen = (10 * resources.displayMetrics.density).toInt()
        for (p in puntos.sortedByDescending { it.nivel }) {
            val tarjeta = LinearLayout(this)
            tarjeta.orientation = LinearLayout.VERTICAL
            tarjeta.setPadding(0, margen, 0, margen)

            val titulo = TextView(this)
            val simbolo = if (p.nivel == Punto.OK) "○ " else "● "
            titulo.text = simbolo + p.titulo
            titulo.textSize = 15f
            titulo.setTypeface(titulo.typeface, Typeface.BOLD)
            val color = when (p.nivel) {
                Punto.AVISO -> R.color.vigia_rojo
                Punto.REVISAR -> R.color.vigia_ambar
                else -> R.color.vigia_gris
            }
            titulo.setTextColor(ContextCompat.getColor(this, color))
            tarjeta.addView(titulo)

            val detalle = TextView(this)
            detalle.text = p.detalle
            detalle.textSize = 13f
            tarjeta.addView(detalle)

            val accion = p.accion
            if (accion != null) {
                val enlace = Ayuda.enlace(this, p.etiquetaAccion + " ›") { abrir(accion) }
                enlace.setPadding(0, margen / 2, margen, margen / 2)
                tarjeta.addView(enlace)
            }
            contenedor.addView(tarjeta)
        }
    }

    private fun abrir(accion: String) {
        try {
            if (accion == Punto.ACCION_APPS) {
                startActivity(Intent(this, AppsActivity::class.java))
            } else {
                startActivity(Intent(accion))
            }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_se_pudo_abrir, Toast.LENGTH_LONG).show()
        }
    }
}
