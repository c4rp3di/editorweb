package com.vigia.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.app.collect.inventory.InventarioResumen
import com.vigia.app.core.SessionManager
import com.vigia.app.util.Formato
import kotlinx.coroutines.launch

/** Muestra la última foto del inventario de una sesión. Los cambios entre fotos están en la línea de tiempo. */
class InventarioActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventario)

        val sesionId = intent.getLongExtra(SessionDetailActivity.EXTRA_SESION, -1L)
        if (sesionId < 0) {
            finish()
            return
        }
        val cabecera = findViewById<TextView>(R.id.invCabecera)
        val texto = findViewById<TextView>(R.id.invTexto)
        findViewById<MaterialButton>(R.id.btnVolver).setOnClickListener { finish() }

        lifecycleScope.launch {
            val resultado = SessionManager.obtener(applicationContext).inventario(sesionId)
            val foto = resultado.first
            if (foto == null) {
                cabecera.text = ""
                texto.setText(R.string.sin_inventario)
            } else {
                cabecera.text = "Sesión #" + sesionId + " · última foto: " + Formato.fecha(foto.ts) +
                    " · fotos guardadas: " + resultado.second
                texto.text = try {
                    InventarioResumen.resumir(foto.payload)
                } catch (e: Exception) {
                    "No se pudo leer la foto del inventario: " + e.javaClass.simpleName
                }
            }
        }
    }
}
