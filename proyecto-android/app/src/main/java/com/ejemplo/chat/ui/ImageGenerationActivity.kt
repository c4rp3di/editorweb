package com.ejemplo.chat.ui

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ejemplo.chat.R
import com.ejemplo.chat.ia.*
import kotlinx.coroutines.*
import java.io.File

class ImageGenerationActivity : AppCompatActivity() {
    private lateinit var etPrompt: EditText
    private lateinit var etNegativo: EditText
    private lateinit var btnGenerar: Button
    private lateinit var btnDetener: Button
    private lateinit var btnDescargar: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEstado: TextView
    private lateinit var ivResultado: ImageView
    private lateinit var imagenes: ModelosImagen
    private var generando = false
    private var vigia: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_generation)

        DebugLog.init(this)
        DiffusionNative.estaDisponible()
        imagenes = ModelosImagen(this)

        etPrompt = findViewById(R.id.etPrompt)
        etNegativo = findViewById(R.id.etNegativo)
        btnGenerar = findViewById(R.id.btnGenerar)
        btnDetener = findViewById(R.id.btnDetener)
        btnDescargar = findViewById(R.id.btnDescargar)
        progressBar = findViewById(R.id.progressBar)
        tvEstado = findViewById(R.id.tvEstado)
        ivResultado = findViewById(R.id.ivResultado)

        etNegativo.setText("borroso, desenfocado, baja calidad, ruido, mancha, deforme, abstracto, marca de agua, firma, texto")
        etPrompt.setText("fotografía realista de un gato naranja, primer plano, ojos verdes, pelo esponjoso, iluminación suave, alta calidad")

        actualizarBotonDescarga()

        btnDescargar.setOnClickListener { descargarModelo() }
        btnGenerar.setOnClickListener { generar() }
        btnDetener.setOnClickListener { GenClient.detener() }
    }

    private fun actualizarBotonDescarga() {
        val m = ModelosImagen.MODELOS.first()
        if (imagenes.descargado(m)) {
            btnDescargar.visibility = View.GONE
            btnGenerar.isEnabled = true
            tvEstado.text = "Modelo listo · ${DiffusionNative.estaDisponible()}"
        } else {
            btnDescargar.visibility = View.VISIBLE
            btnGenerar.isEnabled = false
            tvEstado.text = "Descarga primero el modelo (~4.2 GB)"
        }
    }

    private fun descargarModelo() {
        val m = ModelosImagen.MODELOS.first()
        AlertDialog.Builder(this)
            .setTitle("Descargar ${m.nombre}")
            .setMessage("Se descargarán ~4.2 GB. Se verificará SHA-256. ¿Continuar?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Descargar") { _, _ ->
                lifecycleScope.launch {
                    try {
                        progressBar.visibility = View.VISIBLE
                        progressBar.isIndeterminate = false
                        progressBar.max = 100
                        progressBar.progress = 0
                        btnDescargar.isEnabled = false
                        withContext(Dispatchers.IO) {
                            imagenes.descargar(m) { p -> runOnUiThread { progressBar.progress = p; tvEstado.text = "Descargando… $p%" } }
                        }
                        progressBar.visibility = View.GONE
                        Toast.makeText(this@ImageGenerationActivity, "Modelo verificado", Toast.LENGTH_LONG).show()
                        actualizarBotonDescarga()
                    } catch (e: Exception) {
                        progressBar.visibility = View.GONE
                        btnDescargar.isEnabled = true
                        tvEstado.text = "Error: ${e.message}"
                        Toast.makeText(this@ImageGenerationActivity, e.message ?: "Error", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun generar() {
        val prompt = etPrompt.text.toString().trim()
        if (prompt.isEmpty()) { Toast.makeText(this, "Escribe un prompt", Toast.LENGTH_SHORT).show(); return }
        if (generando) return
        val m = ModelosImagen.MODELOS.first()
        if (!imagenes.descargado(m)) { Toast.makeText(this, "Descarga primero el modelo", Toast.LENGTH_LONG).show(); return }
        if (!DiffusionNative.estaDisponible()) { Toast.makeText(this, "Motor nativo no disponible", Toast.LENGTH_LONG).show(); return }

        generando = true
        btnGenerar.isEnabled = false
        btnDetener.isEnabled = true
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true

        val modelPath = File(imagenes.carpeta(m), m.artefactos.first().fileName).absolutePath
        val outputPath = File(getExternalFilesDir(null), "imagen-${System.currentTimeMillis()}.png").absolutePath
        val negativo = etNegativo.text.toString().trim()

        vigia = lifecycleScope.launch {
            val seg = SeguimientoProgreso(25)
            var s = 0
            while (isActive) {
                delay(1000)
                s++
                val p = GenClient.progreso()
                val e = seg.actualizar(p, s)
                tvEstado.text = e.texto
                if (p.fase >= 2 || e.porcentaje > 5) {
                    progressBar.isIndeterminate = false
                    progressBar.max = 100
                    progressBar.progress = e.porcentaje
                }
                if (s % 5 == 0) DebugLog.log("GEN", "t=${s}s · ${e.texto} · fase=${p.fase} paso=${p.paso}/${p.pasos}")
            }
        }

        lifecycleScope.launch {
            try {
                GenClient.ejecutar(this@ImageGenerationActivity, GenProtocolo.imagen(
                    modelPath, prompt, negativo, outputPath, 512, 512, 25
                ))
                val bmp = BitmapFactory.decodeFile(outputPath)
                ivResultado.setImageBitmap(bmp)
                tvEstado.text = "Listo · $outputPath"
                DebugLog.log("GEN", "imagen generada · $outputPath")
            } catch (e: GenDetenida) {
                tvEstado.text = "Detenido por el usuario"
            } catch (e: Exception) {
                tvEstado.text = "Error: ${e.message}"
                DebugLog.log("GEN", "⚠ ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                vigia?.cancel()
                progressBar.visibility = View.GONE
                generando = false
                btnGenerar.isEnabled = true
                btnDetener.isEnabled = false
            }
        }
    }

    override fun onDestroy() {
        vigia?.cancel()
        super.onDestroy()
    }
}
