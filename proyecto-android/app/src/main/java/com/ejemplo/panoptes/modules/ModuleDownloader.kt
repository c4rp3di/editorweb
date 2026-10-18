package com.carpe.panoptes.modules

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Descarga, verifica (SHA-256 + "firma" = sha256 del DEX publicado en el
 * catálogo firmado) e instala un módulo en filesDir/modules/<id>/ (§5.4).
 *
 * Nunca escribe directamente en el directorio final: todo pasa por un
 * directorio de staging único por operación en cacheDir y solo se mueve a
 * filesDir/modules/<id>/ si todas las verificaciones pasan.
 *
 * Sustitución transaccional (auditoría H-01): si ya había una versión
 * instalada, se aparta a un directorio de respaldo ".<id>.bak-<op>" ANTES de
 * mover la nueva; si el movimiento falla, se restaura el respaldo. El
 * respaldo solo se borra cuando la nueva versión ya está en su sitio.
 * ModuleManager.scanInstalled recupera los respaldos huérfanos si la app se
 * cierra justo a mitad de la sustitución.
 *
 * Concurrencia (H-08): una instalación a la vez por id (Mutex) y nombres
 * temporales únicos por operación.
 *
 * Límites (H-07): tamaño de descarga, nº de entradas, bytes por entrada y
 * bytes totales descomprimidos (contados al copiar, no los declarados en el
 * ZIP, que pueden mentir).
 */
class ModuleDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .followRedirects(false) // las redirecciones se validan a mano contra ALLOWED_HOSTS
        .callTimeout(10, TimeUnit.MINUTES)
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
        require(ModuleManifest.esIdValido(module.id)) { "Id de módulo no válido: ${module.id}" }

        val candado = locks.computeIfAbsent(module.id) { Mutex() }
        candado.withLock { instalar(module, onProgress) }
    }

    private fun instalar(module: ModuleCatalog.Entry, onProgress: (Progress) -> Unit): File {
        val operacion = UUID.randomUUID().toString().take(8)
        val temp = File(context.cacheDir, "${module.id}-$operacion.zip.download")
        val staging = File(context.cacheDir, "${module.id}-staging-$operacion")
        try {
            downloadWithRedirectValidation(module.url, temp) { leidos, total ->
                onProgress(Progress.Downloading(leidos, total))
            }

            onProgress(Progress.Verifying)
            val shaZip = sha256(temp)
            require(shaZip.equals(module.sha256, ignoreCase = true)) {
                "SHA-256 del ZIP no coincide para ${module.id}: $shaZip != ${module.sha256}"
            }

            onProgress(Progress.Unpacking)
            staging.deleteRecursively()
            staging.mkdirs()
            unzip(temp, staging)

            val manifestFile = File(staging, "module.json")
            require(manifestFile.isFile) { "El módulo ${module.id} no contiene module.json" }
            val manifest = requireNotNull(ModuleManifest.parse(manifestFile.readText())) {
                "module.json de ${module.id} no es válido (campos o formato incorrectos)"
            }
            require(manifest.id == module.id) {
                "El id de module.json (${manifest.id}) no coincide con el del catálogo (${module.id})"
            }

            // Se verifica exactamente el DEX que luego cargará ModuleManager (el que nombra
            // module.json), y debe estar dentro del directorio del módulo.
            val dexFile = File(staging, manifest.dexName)
            require(dexFile.isFile && estaDentro(dexFile, staging)) {
                "El módulo ${module.id} no contiene ${manifest.dexName} o está fuera de su directorio"
            }
            val shaDex = sha256(dexFile)
            require(shaDex.equals(module.signatureSha256, ignoreCase = true)) {
                "El SHA-256 del DEX no coincide con el publicado en el catálogo firmado para ${module.id}"
            }
            // Android 14+ exige que el código cargado dinámicamente sea de solo lectura.
            dexFile.setReadOnly()

            val modulesDir = File(context.filesDir, "modules").apply { mkdirs() }
            val dest = File(modulesDir, module.id)
            var respaldo: File? = null
            if (dest.exists()) {
                val bak = File(modulesDir, ".${module.id}$BACKUP_MARK$operacion")
                check(dest.renameTo(bak)) { "No se pudo apartar la versión anterior de ${module.id}" }
                respaldo = bak
            }
            if (!staging.renameTo(dest)) {
                // Rollback: la versión anterior vuelve a su sitio.
                if (respaldo != null) respaldo.renameTo(dest)
                error("No se pudo mover el módulo ${module.id} a su destino final")
            }
            respaldo?.deleteRecursively()

            onProgress(Progress.Done)
            return dest
        } finally {
            temp.delete()
            staging.deleteRecursively()
        }
    }

    private fun estaDentro(archivo: File, dir: File): Boolean =
        archivo.canonicalPath.startsWith(dir.canonicalPath + File.separator)

    private fun downloadWithRedirectValidation(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ) {
        var currentUrl = url
        var hops = 0
        while (hops++ < 5) {
            val parsed = java.net.URL(currentUrl)
            require(parsed.protocol == "https") { "Solo se permite HTTPS: $currentUrl" }
            val host = parsed.host
            require(host in ALLOWED_HOSTS) { "Host no permitido: $host" }

            val req = Request.Builder().url(currentUrl).build()
            client.newCall(req).execute().use { res ->
                when (res.code) {
                    in 200..299 -> {
                        val total = res.body?.contentLength() ?: -1
                        require(total <= MAX_DESCARGA_BYTES) { "La descarga supera el límite de ${MAX_DESCARGA_BYTES / 1048576} MB" }
                        res.body!!.byteStream().use { input ->
                            dest.outputStream().use { output ->
                                val buf = ByteArray(16 * 1024)
                                var leidos = 0L
                                var n: Int
                                while (input.read(buf).also { n = it } > 0) {
                                    leidos += n
                                    require(leidos <= MAX_DESCARGA_BYTES) { "La descarga supera el límite de ${MAX_DESCARGA_BYTES / 1048576} MB" }
                                    output.write(buf, 0, n)
                                    onProgress(leidos, total)
                                }
                            }
                        }
                        return
                    }
                    in 300..399 -> {
                        val destino = res.header("Location") ?: error("Redirección sin Location")
                        // Location puede ser relativa: se resuelve contra la URL actual.
                        currentUrl = java.net.URL(parsed, destino).toString()
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
        val destCanonica = dest.canonicalPath
        val vistos = HashSet<String>()
        var entradas = 0
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entradas++
                require(entradas <= MAX_ENTRADAS) { "El ZIP tiene demasiadas entradas (máx. $MAX_ENTRADAS)" }
                val target = File(dest, entry.name)
                val canonica = target.canonicalPath
                require(canonica.startsWith(destCanonica + File.separator)) {
                    "Zip slip detectado: ${entry.name}"
                }
                require(vistos.add(canonica)) { "Entrada duplicada en el ZIP: ${entry.name}" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { salida ->
                        val buf = ByteArray(16 * 1024)
                        var enEntrada = 0L
                        var n: Int
                        while (zis.read(buf).also { n = it } > 0) {
                            enEntrada += n
                            total += n
                            require(enEntrada <= MAX_ENTRADA_BYTES) { "Una entrada del ZIP supera el límite de ${MAX_ENTRADA_BYTES / 1048576} MB" }
                            require(total <= MAX_TOTAL_BYTES) { "El ZIP descomprimido supera el límite de ${MAX_TOTAL_BYTES / 1048576} MB" }
                            salida.write(buf, 0, n)
                        }
                    }
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

        const val BACKUP_MARK = ".bak-"

        private const val MAX_DESCARGA_BYTES = 200L * 1024 * 1024
        private const val MAX_ENTRADA_BYTES = 150L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 400L * 1024 * 1024
        private const val MAX_ENTRADAS = 2000

        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
