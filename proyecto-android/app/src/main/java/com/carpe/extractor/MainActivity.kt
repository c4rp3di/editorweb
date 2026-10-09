package com.carpe.panoptes

import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.carpe.panoptes.ingest.ApkExtractor
import com.carpe.panoptes.ui.apps.AppAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var extractor: ApkExtractor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        extractor = ApkExtractor(this)

        val recycler = findViewById<RecyclerView>(R.id.listaApps)
        recycler.layoutManager = LinearLayoutManager(this)

        val adaptador = AppAdapter { info -> extraerYRegistrar(info) }
        recycler.adapter = adaptador

        adaptador.actualizar(extractor.listarAppsInstaladas())
    }

    private fun extraerYRegistrar(info: PackageInfo) {
        try {
            val resultado = extractor.extraer(info)
            if (resultado.uris.isEmpty()) {
                Toast.makeText(this, getString(R.string.extraccion_error), Toast.LENGTH_LONG).show()
                return
            }

            // Registrar el análisis en Room (pendiente — el pipeline real llega en F1)
            val app = (application as PanoptesApp)
            lifecycleScope.launch {
                app.analysisRepository.registrarExtraccion(
                    packageName = info.packageName,
                    versionName = info.versionName,
                    apkPath = resultado.archivos.first().absolutePath,
                    sha256 = resultado.sha256Base
                )
            }

            val intent = if (resultado.uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, resultado.uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "application/vnd.android.package-archive"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(resultado.uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(Intent.createChooser(intent, getString(R.string.compartir_apk)))

            Toast.makeText(
                this,
                "${getString(R.string.extraccion_ok)} ${resultado.uris.size} archivo(s)",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "${getString(R.string.extraccion_error)} ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
