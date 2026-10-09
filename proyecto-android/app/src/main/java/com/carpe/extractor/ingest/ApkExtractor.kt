package com.carpe.panoptes.ingest

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Listado y extracción de APKs instaladas.
 * Es el código del extractor original (MainActivity.extraerApk), movido
 * fuera de la Activity para que :core y el pipeline de análisis (F1+)
 * puedan reutilizarlo sin depender de la UI.
 */
class ApkExtractor(private val context: Context) {

    data class InstalledApp(
        val nombre: String,
        val paquete: String,
        val icono: Drawable,
        val info: PackageInfo
    )

    data class ExtractedApk(
        val archivos: List<File>,
        val uris: List<Uri>,
        val sha256Base: String
    )

    fun listarAppsInstaladas(incluirSistema: Boolean = false): List<InstalledApp> {
        val pm = context.packageManager
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
                if (!incluirSistema && (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) {
                    return@mapNotNull null
                }
                InstalledApp(
                    nombre = app.loadLabel(pm).toString(),
                    paquete = pkg.packageName,
                    icono = app.loadIcon(pm),
                    info = pkg
                )
            }
            .sortedBy { it.nombre.lowercase() }
            .toList()
    }

    /**
     * Copia el APK (base + splits) a cacheDir/apks/ y genera Uris de FileProvider
     * listas para compartir o para que el pipeline de análisis las consuma.
     */
    fun extraer(info: PackageInfo): ExtractedApk {
        val app = requireNotNull(info.applicationInfo) { "PackageInfo sin applicationInfo" }
        val version = info.versionName ?: "desconocida"
        val dirSalida = File(context.cacheDir, "apks").apply { mkdirs() }

        val rutas = mutableListOf(File(app.sourceDir))
        app.splitSourceDirs?.forEach { ruta -> rutas.add(File(ruta)) }

        val archivos = mutableListOf<File>()
        val uris = mutableListOf<Uri>()
        var shaBase = ""

        rutas.forEachIndexed { idx, origen ->
            if (!origen.exists()) return@forEachIndexed
            val destino = File(dirSalida, "${info.packageName}-$version-${origen.name}")
            FileInputStream(origen).use { entrada ->
                FileOutputStream(destino).use { salida -> entrada.copyTo(salida) }
            }
            if (idx == 0) shaBase = sha256(destino)
            archivos.add(destino)
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destino))
        }

        return ExtractedApk(archivos, uris, shaBase)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
