package com.carpe.panoptes.modules

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Descarga, verifica (SHA-256 + "firma" = sha256 del DEX publicado en el
 * catálogo firmado) e instala un módulo en filesDir/modules/<id>/ (§5.4).
 *
 * Nunca escribe directamente en el directorio final: todo pasa por
 * cacheDir/<id>-staging/ y solo se mueve a filesDir/modules/<id>/ si todas
 * las verificaciones pasan. Si algo fallara a medias, el directorio final
 * nunca queda en un estado parcial/corrupto.
 */
class ModuleDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .followRedirects(false) // las redirecciones se validan a mano contra ALLOWED_HOSTS
        .build()

    sealed class Progress {
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : Progress()
        object Verifying : Progress()
        object Unpacking : Progress()
        object Done : Progress()
    }

    suspend fun downloadAndInstall(
        module: ModuleCatalog.Entry,
        onProgress: (Progress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(module.publicado) { "El módulo ${module.id} todavía no está publicado (sha256 pendiente en el catálogo)" }

        val temp = File(context.cacheDir, "${module.id}-${module.version}.zip.download")
        downloadWithRedirectValidation(module.url, temp) { leidos, total ->
            onProgress(Progress.Downloading(leidos, total))
        }

        onProgress(Progress.Verifying)
        val shaZip = sha256(temp)
        require(shaZip.equals(module.sha256, ignoreCase = true)) {
            "SHA-256 del ZIP no coincide para ${module.id}: $shaZip != ${module.sha256}"
        }

        onProgress(Progress.Unpacking)
        val staging = File(context.cacheDir, "${module.id}-staging").apply {
            deleteRecursively(); mkdirs()
        }
        unzip(temp, staging)

        val dexFile = File(staging, "classes.dex")
        require(dexFile.exists()) { "El módulo ${module.id} no contiene classes.dex" }
        val shaDex = sha256(dexFile)
        require(shaDex.equals(module.signatureSha256, ignoreCase = true)) {
            "El SHA-256 del DEX no coincide con el publicado en el catálogo firmado para ${module.id}"
        }

        val manifestFile = File(staging, "module.json")
        require(manifestFile.exists()) { "El módulo ${module.id} no contiene module.json" }

        val dest = File(context.filesDir, "modules/${module.id}")
        dest.deleteRecursively()
        File(context.filesDir, "modules").mkdirs()
        check(staging.renameTo(dest)) { "No se pudo mover el módulo ${module.id} a su destino final" }

        temp.delete()
        onProgress(Progress.Done)
        dest
    }

    private fun downloadWithRedirectValidation(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ) {
        var currentUrl = url
        var hops = 0
        while (hops++ < 5) {
            val host = java.net.URL(currentUrl).host
            require(host in ALLOWED_HOSTS) { "Host no permitido: $host" }

            val req = Request.Builder().url(currentUrl).build()
            client.newCall(req).execute().use { res ->
                when (res.code) {
                    in 200..299 -> {
                        val total = res.body?.contentLength() ?: -1
                        res.body!!.byteStream().use { input ->
                            dest.outputStream().use { output ->
                                val buf = ByteArray(16 * 1024)
                                var leidos = 0L
                                var n: Int
                                while (input.read(buf).also { n = it } > 0) {
                                    output.write(buf, 0, n)
                                    leidos += n
                                    onProgress(leidos, total)
                                }
                            }
                        }
                        return
                    }
                    in 300..399 -> {
                        currentUrl = res.header("Location") ?: error("Redirección sin Location")
                    }
                    else -> error("HTTP ${res.code} al descargar módulo")
                }
            }
        }
        error("Demasiadas redirecciones")
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

    private fun unzip(zip: File, dest: File) {
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(dest, entry.name)
                require(target.canonicalPath.startsWith(dest.canonicalPath + File.separator)) {
                    "Zip slip detectado: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        /** Mismo principio que §5.4 del plan: solo GitHub y sus CDNs de releases. */
        val ALLOWED_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "raw.githubusercontent.com"
        )
    }
}
