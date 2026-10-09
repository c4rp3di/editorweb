package com.carpe.extractor

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recycler = findViewById<RecyclerView>(R.id.listaApps)
        recycler.layoutManager = LinearLayoutManager(this)

        val adaptador = AppAdapter { info -> extraerApk(info) }
        recycler.adapter = adaptador

        adaptador.actualizar(cargarAppsInstaladas())
    }

    private fun cargarAppsInstaladas(): List<AppItem> {
        val pm = packageManager
        val paquetes: List<PackageInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }

        return paquetes.asSequence()
            .mapNotNull { pkg ->
                val app = pkg.applicationInfo ?: return@mapNotNull null
                if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
                AppItem(
                    nombre = app.loadLabel(pm).toString(),
                    paquete = pkg.packageName,
                    icono = app.loadIcon(pm),
                    info = pkg
                )
            }
            .sortedBy { it.nombre.lowercase() }
            .toList()
    }
private fun extraerApk(info: PackageInfo) {
    try {
        val app = info.applicationInfo ?: return
        val version = info.versionName ?: "desconocida"
        val dirSalida = File(cacheDir, "apks").apply { mkdirs() }

        // Reunimos base + splits (si los hay)
        val rutas = mutableListOf(File(app.sourceDir))
        app.splitSourceDirs?.forEach { ruta -> rutas.add(File(ruta)) }

        val uris = ArrayList<android.net.Uri>()
        rutas.forEach { origen ->
            if (!origen.exists()) return@forEach
            val destino = File(dirSalida, "${info.packageName}-$version-${origen.name}")
            FileInputStream(origen).use { entrada ->
                FileOutputStream(destino).use { salida -> entrada.copyTo(salida) }
            }
            uris.add(
                FileProvider.getUriForFile(this, "$packageName.fileprovider", destino)
            )
        }

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/vnd.android.package-archive"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.extractor_compartir)))

        Toast.makeText(
            this,
            "${getString(R.string.extractor_ok)} ${uris.size} archivo(s)",
            Toast.LENGTH_LONG
        ).show()
    } catch (e: Exception) {
        Toast.makeText(
            this,
            "${getString(R.string.extractor_error)} ${e.message}",
            Toast.LENGTH_LONG
        ).show()
    }
}


}

data class AppItem(
    val nombre: String,
    val paquete: String,
    val icono: Drawable,
    val info: PackageInfo
)

class AppAdapter(private val onClick: (PackageInfo) -> Unit) :
    RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var items: List<AppItem> = emptyList()

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val icono: ImageView = v.findViewById(R.id.iconoApp)
        val nombre: TextView = v.findViewById(R.id.nombreApp)
        val paquete: TextView = v.findViewById(R.id.paqueteApp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.icono.setImageDrawable(item.icono)
        holder.nombre.text = item.nombre
        holder.paquete.text = item.paquete
        holder.itemView.setOnClickListener { onClick(item.info) }
    }

    override fun getItemCount(): Int = items.size

    fun actualizar(nuevos: List<AppItem>) {
        items = nuevos
        notifyDataSetChanged()
    }
}