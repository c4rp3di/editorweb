package com.vigia.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.app.integridad.PlanLimpieza
import com.vigia.app.util.Formato
import com.vigia.app.util.Portapapeles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Plan de limpieza: las apps elegidas y los comandos de ADB para desactivarlas o quitarlas, con su «deshacer». */
class PlanLimpiezaActivity : AppCompatActivity() {

    private lateinit var contenedor: LinearLayout
    private lateinit var vacio: TextView
    private lateinit var guion: TextView
    private var texto: String = ""

    private val lanzadorGuardar = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        guardar(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan_limpieza)
        contenedor = findViewById(R.id.contenedorPlan)
        vacio = findViewById(R.id.planVacio)
        guion = findViewById(R.id.planGuion)

        findViewById<MaterialButton>(R.id.btnVolver).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnCopiarPlan).setOnClickListener {
            if (texto.isNotEmpty()) {
                Portapapeles.copiar(this, texto)
                Toast.makeText(this, R.string.copiado, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<MaterialButton>(R.id.btnGuardarPlan).setOnClickListener {
            if (texto.isNotEmpty()) lanzadorGuardar.launch("vigia-plan-limpieza.sh")
        }
        findViewById<MaterialButton>(R.id.btnCompartirPlan).setOnClickListener {
            if (texto.isNotEmpty()) {
                val envio = Intent(Intent.ACTION_SEND)
                envio.type = "text/plain"
                envio.putExtra(Intent.EXTRA_TEXT, texto)
                startActivity(Intent.createChooser(envio, getString(R.string.compartir_plan)))
            }
        }
        findViewById<MaterialButton>(R.id.btnVaciarPlan).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.vaciar_plan)
                .setMessage(R.string.confirmar_vaciar_plan)
                .setPositiveButton(R.string.vaciar_plan) { _, _ ->
                    PlanLimpieza.vaciar(this)
                    refrescar()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        refrescar()
    }

    private fun refrescar() {
        val lista = PlanLimpieza.leer(this)
        contenedor.removeAllViews()
        vacio.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE
        val margen = (8 * resources.displayMetrics.density).toInt()
        for (e in lista) {
            val fila = TextView(this)
            val accion = if (e.accion == PlanLimpieza.DESACTIVAR) "desactivar" else "quitar para este usuario"
            fila.text = e.etiqueta + "\n" + e.paquete + " · " + accion
            fila.textSize = 14f
            fila.setPadding(0, margen, 0, margen)
            fila.setOnClickListener { opciones(e) }
            contenedor.addView(fila)
        }
        texto = if (lista.isEmpty()) "" else PlanLimpieza.guion(lista, Formato.fecha(System.currentTimeMillis()))
        guion.text = texto
    }

    private fun opciones(e: PlanLimpieza.Entrada) {
        val cambio = if (e.accion == PlanLimpieza.DESACTIVAR) "Cambiar a «quitar para este usuario»" else "Cambiar a «desactivar»"
        AlertDialog.Builder(this)
            .setTitle(e.etiqueta)
            .setItems(arrayOf(cambio, "Quitar del plan")) { _, cual ->
                if (cual == 0) {
                    val nueva = if (e.accion == PlanLimpieza.DESACTIVAR) PlanLimpieza.DESINSTALAR else PlanLimpieza.DESACTIVAR
                    PlanLimpieza.anadir(this, e.paquete, e.etiqueta, nueva)
                } else {
                    PlanLimpieza.quitar(this, e.paquete)
                }
                refrescar()
            }
            .show()
    }

    private fun guardar(uri: Uri?) {
        if (uri == null) return
        val bytes = texto.toByteArray(Charsets.UTF_8)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(this@PlanLimpiezaActivity, if (ok) R.string.informe_guardado else R.string.informe_no_guardado, Toast.LENGTH_LONG).show()
        }
    }
}
