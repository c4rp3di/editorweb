package com.vigia.app

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.vigia.app.core.Retencion
import com.vigia.app.core.SessionManager
import com.vigia.app.informe.Firma
import com.vigia.app.informe.InformeCifrado
import com.vigia.app.informe.InformeGenerador
import com.vigia.app.informe.InformePdf
import com.vigia.app.util.Formato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Informe final de una sesión: vista previa, exportación cifrada (.vigia) y PDF legible. */
class InformeActivity : AppCompatActivity() {

    private lateinit var gestor: SessionManager
    private lateinit var infEstado: TextView
    private lateinit var infResumen: TextView
    private lateinit var chkCrudos: MaterialCheckBox
    private lateinit var btnExportarCifrado: MaterialButton
    private lateinit var btnExportarPdf: MaterialButton
    private lateinit var btnRegenerar: MaterialButton
    private lateinit var btnBorrarCrudos: MaterialButton
    private var sesionId = -1L
    private var bytesPendientes: ByteArray? = null

    private val lanzadorCifrado = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> escribir(uri) }

    private val lanzadorPdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> escribir(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_informe)

        sesionId = intent.getLongExtra(SessionDetailActivity.EXTRA_SESION, -1L)
        if (sesionId < 0) {
            finish()
            return
        }
        gestor = SessionManager.obtener(applicationContext)

        infEstado = findViewById(R.id.infEstado)
        infResumen = findViewById(R.id.infResumen)
        chkCrudos = findViewById(R.id.chkCrudos)
        btnExportarCifrado = findViewById(R.id.btnExportarCifrado)
        btnExportarPdf = findViewById(R.id.btnExportarPdf)
        btnRegenerar = findViewById(R.id.btnRegenerar)
        btnBorrarCrudos = findViewById(R.id.btnBorrarCrudos)

        findViewById<MaterialButton>(R.id.btnVolver).setOnClickListener { finish() }
        btnExportarCifrado.setOnClickListener { pedirContrasena() }
        btnExportarPdf.setOnClickListener { confirmarPdf() }
        btnRegenerar.setOnClickListener { regenerar() }
        btnBorrarCrudos.setOnClickListener { confirmarBorrarCrudos() }

        refrescar()
    }

    private fun refrescar() {
        lifecycleScope.launch {
            val sesion = gestor.sesion(sesionId)
            if (sesion == null) {
                finish()
                return@launch
            }
            val borrados = SessionManager.crudosBorradosDe(sesion.configuracion)
            val cerrada = sesion.fin != null
            val sobre = InformeGenerador.guardado(applicationContext, sesionId)

            val estado = StringBuilder()
            if (!cerrada) estado.append("La sesión sigue abierta: el informe definitivo se genera al cerrarla.\n")
            if (borrados > 0L) estado.append("Datos crudos borrados el ").append(Formato.fecha(borrados)).append(".\n")
            if (sobre == null) {
                estado.append("Todavía no hay informe guardado para esta sesión.")
                infResumen.text = ""
            } else {
                val contenido = contenidoDe(sobre)
                estado.append("Informe firmado generado el ").append(Formato.fecha(contenido?.optLong("generado") ?: 0L)).append(".")
                infResumen.text = if (contenido != null) resumenTexto(contenido) else ""
            }
            infEstado.text = estado.toString()

            val hayCrudos = borrados == 0L
            chkCrudos.isEnabled = hayCrudos && cerrada
            if (!chkCrudos.isEnabled) chkCrudos.isChecked = false
            btnRegenerar.isEnabled = hayCrudos && cerrada
            btnBorrarCrudos.isEnabled = hayCrudos && cerrada
            btnExportarCifrado.isEnabled = cerrada || sobre != null
            btnExportarPdf.isEnabled = sobre != null || cerrada
        }
    }

    private fun contenidoDe(sobre: String): JSONObject? {
        return try {
            JSONObject(JSONObject(sobre).getString("contenido"))
        } catch (e: Exception) {
            null
        }
    }

    private fun resumenTexto(c: JSONObject): String {
        val r = c.optJSONObject("resumen") ?: JSONObject()
        val e = c.optJSONObject("estadisticas") ?: JSONObject()
        return r.optString("texto") + "\n\nCríticos " + r.optInt("criticas") + " · Altos " + r.optInt("altas") +
            " · Medios " + r.optInt("medias") + " · Bajos " + r.optInt("bajas") + "\n\n" +
            "Consultas DNS: " + e.optInt("consultas_dns") + " · dominios únicos: " + e.optInt("dominios_unicos") +
            "\nEventos de sistema: " + e.optInt("eventos_sistema") + " · cámara/micrófono: " + e.optInt("eventos_sensores") +
            " · pantalla: " + e.optInt("eventos_pantalla")
    }

    private fun regenerar() {
        btnRegenerar.isEnabled = false
        lifecycleScope.launch {
            InformeGenerador.generarYGuardar(applicationContext, sesionId)
            Toast.makeText(this@InformeActivity, R.string.informe_actualizado, Toast.LENGTH_SHORT).show()
            refrescar()
        }
    }

    // ------------------------------------------------------------------ exportación cifrada

    private fun pedirContrasena() {
        val contenedor = LinearLayout(this)
        contenedor.orientation = LinearLayout.VERTICAL
        val margen = (16 * resources.displayMetrics.density).toInt()
        contenedor.setPadding(margen, margen / 2, margen, 0)
        val uno = EditText(this)
        uno.hint = getString(R.string.contrasena)
        uno.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val dos = EditText(this)
        dos.hint = getString(R.string.repetir_contrasena)
        dos.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        contenedor.addView(uno)
        contenedor.addView(dos)

        AlertDialog.Builder(this)
            .setTitle(R.string.contrasena_titulo)
            .setMessage(R.string.contrasena_mensaje)
            .setView(contenedor)
            .setPositiveButton(R.string.exportar) { _, _ ->
                val a = uno.text.toString()
                val b = dos.text.toString()
                if (a.length < 8) {
                    Toast.makeText(this, R.string.contrasena_corta, Toast.LENGTH_LONG).show()
                } else if (a != b) {
                    Toast.makeText(this, R.string.contrasena_distinta, Toast.LENGTH_LONG).show()
                } else {
                    prepararCifrado(a.toCharArray())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun prepararCifrado(contrasena: CharArray) {
        btnExportarCifrado.isEnabled = false
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    val sobre = sobreParaExportar() ?: throw IllegalStateException("No hay informe que exportar")
                    val contenido = JSONObject(JSONObject(sobre).getString("contenido"))
                    InformeCifrado.paquete(sobre, InformePdf.generar(contenido), contrasena)
                }
                bytesPendientes = bytes
                lanzadorCifrado.launch("vigia-informe-sesion-" + sesionId + ".vigia")
            } catch (e: Exception) {
                Toast.makeText(this@InformeActivity, "No se pudo preparar el informe: " + e.javaClass.simpleName + " " + (e.message ?: ""), Toast.LENGTH_LONG).show()
            }
            btnExportarCifrado.isEnabled = true
        }
    }

    /** El informe guardado, o uno nuevo con datos crudos si el usuario lo pidió y todavía existen. */
    private suspend fun sobreParaExportar(): String? {
        if (chkCrudos.isChecked) {
            val contenido = InformeGenerador.construir(applicationContext, sesionId, true) ?: return null
            return Firma.sobre(contenido).toString()
        }
        val guardado = InformeGenerador.guardado(applicationContext, sesionId)
        if (guardado != null) return guardado
        if (!InformeGenerador.generarYGuardar(applicationContext, sesionId)) return null
        return InformeGenerador.guardado(applicationContext, sesionId)
    }

    // ------------------------------------------------------------------ PDF sin cifrar

    private fun confirmarPdf() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exportar_pdf)
            .setMessage(R.string.aviso_pdf)
            .setPositiveButton(R.string.exportar) { _, _ -> prepararPdf() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun prepararPdf() {
        btnExportarPdf.isEnabled = false
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    val sobre = sobreParaPdf() ?: throw IllegalStateException("No hay informe que exportar")
                    InformePdf.generar(JSONObject(JSONObject(sobre).getString("contenido")))
                }
                bytesPendientes = bytes
                lanzadorPdf.launch("vigia-informe-sesion-" + sesionId + ".pdf")
            } catch (e: Exception) {
                Toast.makeText(this@InformeActivity, "No se pudo preparar el PDF: " + e.javaClass.simpleName + " " + (e.message ?: ""), Toast.LENGTH_LONG).show()
            }
            btnExportarPdf.isEnabled = true
        }
    }

    private suspend fun sobreParaPdf(): String? {
        val guardado = InformeGenerador.guardado(applicationContext, sesionId)
        if (guardado != null) return guardado
        if (!InformeGenerador.generarYGuardar(applicationContext, sesionId)) return null
        return InformeGenerador.guardado(applicationContext, sesionId)
    }

    private fun escribir(uri: Uri?) {
        val bytes = bytesPendientes
        bytesPendientes = null
        if (uri == null || bytes == null) return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(
                this@InformeActivity,
                if (ok) R.string.informe_guardado else R.string.informe_no_guardado,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ------------------------------------------------------------------ borrado de datos crudos

    private fun confirmarBorrarCrudos() {
        AlertDialog.Builder(this)
            .setTitle(R.string.borrar_crudos)
            .setMessage(R.string.confirmar_borrar_crudos)
            .setPositiveButton(R.string.borrar_crudos) { _, _ ->
                lifecycleScope.launch {
                    val ok = Retencion.borrarCrudos(applicationContext, sesionId)
                    if (!ok) Toast.makeText(this@InformeActivity, R.string.no_se_pudo_borrar, Toast.LENGTH_LONG).show()
                    refrescar()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
