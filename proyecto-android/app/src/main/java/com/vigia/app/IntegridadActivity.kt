package com.vigia.app

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.app.informe.VerificarInforme
import com.vigia.app.integridad.Atestacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Integridad: atestación por hardware del sistema y verificación de informes .vigia sin necesidad de un ordenador. */
class IntegridadActivity : AppCompatActivity() {

    private lateinit var txtAtestacion: TextView
    private lateinit var txtVerificacion: TextView
    private lateinit var btnAtestacion: MaterialButton
    private lateinit var btnGuardarCadena: MaterialButton
    private lateinit var btnGuardarPdf: MaterialButton
    private var cadenaPem: String = ""
    private var pdfDescifrado: ByteArray? = null
    private var bytesPendientes: ByteArray? = null

    private val lanzadorAbrir = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pedirContrasena(uri)
    }
    private val lanzadorCadena = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri ->
        escribir(uri)
    }
    private val lanzadorPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        escribir(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_integridad)

        txtAtestacion = findViewById(R.id.txtAtestacion)
        txtVerificacion = findViewById(R.id.txtVerificacion)
        btnAtestacion = findViewById(R.id.btnAtestacion)
        btnGuardarCadena = findViewById(R.id.btnGuardarCadena)
        btnGuardarPdf = findViewById(R.id.btnGuardarPdf)

        findViewById<MaterialButton>(R.id.btnVolver).setOnClickListener { finish() }
        btnAtestacion.setOnClickListener { comprobarSistema() }
        btnGuardarCadena.setOnClickListener {
            bytesPendientes = cadenaPem.toByteArray(Charsets.UTF_8)
            lanzadorCadena.launch("vigia-cadena-atestacion.pem")
        }
        findViewById<MaterialButton>(R.id.btnAbrirInforme).setOnClickListener {
            lanzadorAbrir.launch(arrayOf("*/*"))
        }
        btnGuardarPdf.setOnClickListener {
            val pdf = pdfDescifrado
            if (pdf != null) {
                bytesPendientes = pdf
                lanzadorPdf.launch("vigia-informe.pdf")
            }
        }
    }

    private fun comprobarSistema() {
        btnAtestacion.isEnabled = false
        txtAtestacion.setText(R.string.comprobando)
        lifecycleScope.launch {
            val r = Atestacion.comprobar()
            cadenaPem = r.cadenaPem
            btnGuardarCadena.isEnabled = r.cadenaPem.isNotEmpty()
            txtAtestacion.text = r.texto
            btnAtestacion.isEnabled = true
        }
    }

    private fun pedirContrasena(uri: Uri) {
        val campo = EditText(this)
        campo.hint = getString(R.string.contrasena)
        campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val contenedor = LinearLayout(this)
        val margen = (16 * resources.displayMetrics.density).toInt()
        contenedor.setPadding(margen, margen / 2, margen, 0)
        contenedor.addView(campo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle(R.string.contrasena_titulo_abrir)
            .setView(contenedor)
            .setPositiveButton(R.string.abrir) { _, _ ->
                val pass = campo.text.toString()
                if (pass.isNotEmpty()) verificar(uri, pass.toCharArray())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun verificar(uri: Uri, contrasena: CharArray) {
        txtVerificacion.setText(R.string.verificando)
        btnGuardarPdf.isEnabled = false
        lifecycleScope.launch {
            val resultado = withContext(Dispatchers.Default) {
                val datos = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }
                if (datos == null) null else VerificarInforme.analizar(datos, contrasena)
            }
            if (resultado == null) {
                txtVerificacion.setText(R.string.no_se_pudo_leer)
            } else {
                txtVerificacion.text = resultado.texto
                pdfDescifrado = resultado.pdf
                btnGuardarPdf.isEnabled = resultado.pdf != null
            }
        }
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
            Toast.makeText(this@IntegridadActivity, if (ok) R.string.informe_guardado else R.string.informe_no_guardado, Toast.LENGTH_LONG).show()
        }
    }
}
