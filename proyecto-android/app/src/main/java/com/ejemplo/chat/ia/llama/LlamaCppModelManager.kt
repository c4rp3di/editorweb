package com.ejemplo.chat.ia.llama

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Gestiona exclusivamente el ciclo Descargar -> Verificar.
 * Nunca descarga nada por si sola.
 */
class LlamaCppModelManager(private val context: Context) {

    enum class Estado {
        NO_DESCARGADO,
        DESCARGANDO,
        VERIFICANDO,
        LISTO,
        ERROR
    }

    data class Progreso(
        val bytesActuales: Long,
        val bytesTotales: Long,
        val porcentaje: Int
    )

    data class ResultadoVerificacion(
        val valido: Boolean,
        val existe: Boolean,
        val tamanoBytes: Long,
        val sha256: String?,
        val gguf: Boolean,
        val mensaje: String
    )

    @Volatile
    var estado: Estado = Estado.NO_DESCARGADO
        private set

    private fun carpetaModelos(): File =
        File(context.filesDir, "llama/models").apply { mkdirs() }

    fun archivoDe(modelo: LlamaCppModel): File =
        File(carpetaModelos(), modelo.archivo)

    private fun parcialDe(modelo: LlamaCppModel): File =
        File(carpetaModelos(), modelo.archivo + ".part")

    fun modeloListo(modelo: LlamaCppModel): Boolean = archivoDe(modelo).isFile

    fun eliminarModelo(modelo: LlamaCppModel): Boolean {
        val destino = archivoDe(modelo)
        val parcial = parcialDe(modelo)
        return (!destino.exists() || destino.delete()) &&
            (!parcial.exists() || parcial.delete())
    }

    suspend fun verificarModelo(modelo: LlamaCppModel): ResultadoVerificacion =
        withContext(Dispatchers.IO) {
            estado = Estado.VERIFICANDO
            val archivo = archivoDe(modelo)
            if (!archivo.isFile) {
                estado = Estado.NO_DESCARGADO
                return@withContext ResultadoVerificacion(
                    valido = false,
                    existe = false,
                    tamanoBytes = 0L,
                    sha256 = null,
                    gguf = false,
                    mensaje = "El modelo todavía no está descargado."
                )
            }

            try {
                val gguf = tieneCabeceraGguf(archivo)
                if (!gguf) {
                    estado = Estado.ERROR
                    return@withContext ResultadoVerificacion(
                        valido = false,
                        existe = true,
                        tamanoBytes = archivo.length(),
                        sha256 = null,
                        gguf = false,
                        mensaje = "El archivo existe, pero no tiene una cabecera GGUF válida."
                    )
                }

                val hash = sha256(archivo)
                val valido = hash.equals(modelo.sha256, ignoreCase = true)
                estado = if (valido) Estado.LISTO else Estado.ERROR
                ResultadoVerificacion(
                    valido = valido,
                    existe = true,
                    tamanoBytes = archivo.length(),
                    sha256 = hash,
                    gguf = true,
                    mensaje = if (valido) {
                        "Modelo verificado correctamente."
                    } else {
                        "SHA-256 no coincide con el esperado."
                    }
                )
            } catch (e: Exception) {
                estado = Estado.ERROR
                ResultadoVerificacion(
                    valido = false,
                    existe = true,
                    tamanoBytes = archivo.length(),
                    sha256 = null,
                    gguf = false,
                    mensaje = "No se pudo verificar el modelo: ${e.message ?: "error desconocido"}"
                )
            }
        }

    /**
     * Descarga manual y reanudable. Solo después de verificar el .part se
     * sustituye el archivo final.
     */
    suspend fun descargarModelo(
        modelo: LlamaCppModel,
        onProgreso: (Progreso) -> Unit = {}
    ) {
        val existente = archivoDe(modelo)
        if (existente.isFile) {
            val verificacion = verificarModelo(modelo)
            if (verificacion.valido) return
        }

        estado = Estado.DESCARGANDO
        try {
            withContext(Dispatchers.IO) {
                val parcial = parcialDe(modelo)
                val destino = archivoDe(modelo)
                val yaBajado = if (parcial.isFile) parcial.length() else 0L

                val conexion = (URL(modelo.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/octet-stream")
                    if (yaBajado > 0L) setRequestProperty("Range", "bytes=$yaBajado-")
                }

                try {
                    val codigo = conexion.responseCode
                    if (codigo != HttpURLConnection.HTTP_OK && codigo != HttpURLConnection.HTTP_PARTIAL) {
                        throw IllegalStateException("Descarga rechazada (HTTP $codigo)")
                    }

                    // Si el servidor no admite Range y devuelve 200, se reinicia
                    // la descarga para no duplicar bytes sobre el .part existente.
                    val reanuda = codigo == HttpURLConnection.HTTP_PARTIAL && yaBajado > 0L
                    val base = if (reanuda) yaBajado else 0L
                    if (!reanuda && parcial.exists()) parcial.delete()

                    val longitudRespuesta = conexion.contentLengthLong
                    val total = if (longitudRespuesta > 0L) base + longitudRespuesta else -1L
                    var actual = base
                    var ultimoPorcentaje = -1

                    conexion.inputStream.use { entrada ->
                        FileOutputStream(parcial, reanuda).use { salida ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val leidos = entrada.read(buffer)
                                if (leidos < 0) break
                                if (leidos == 0) continue
                                salida.write(buffer, 0, leidos)
                                actual += leidos
                                ensureActive()

                                val porcentaje = if (total > 0L) {
                                    ((actual * 100L) / total).toInt().coerceIn(0, 100)
                                } else {
                                    -1
                                }
                                if (porcentaje != ultimoPorcentaje) {
                                    ultimoPorcentaje = porcentaje
                                    withContext(Dispatchers.Main) {
                                        onProgreso(Progreso(actual, total, porcentaje))
                                    }
                                }
                            }
                            salida.fd.sync()
                        }
                    }

                    if (!parcial.isFile || parcial.length() == 0L) {
                        throw IllegalStateException("La descarga terminó sin contenido")
                    }
                } finally {
                    conexion.disconnect()
                }

                estado = Estado.VERIFICANDO
                val ggufPart = tieneCabeceraGguf(parcial)
                if (!ggufPart) {
                    throw IllegalStateException("La descarga no contiene un archivo GGUF válido")
                }
                val hashPart = sha256(parcial)
                if (!hashPart.equals(modelo.sha256, ignoreCase = true)) {
                    throw IllegalStateException("SHA-256 no coincide con el modelo esperado")
                }

                // El archivo final solo aparece después de la verificación.
                if (destino.exists() && !destino.delete()) {
                    throw IllegalStateException("No se pudo reemplazar el modelo anterior")
                }
                if (!parcial.renameTo(destino)) {
                    throw IllegalStateException("No se pudo activar el modelo verificado")
                }
            }
            estado = Estado.LISTO
        } catch (e: CancellationException) {
            estado = Estado.NO_DESCARGADO
            throw e
        } catch (e: Exception) {
            estado = Estado.ERROR
            throw e
        }
    }

    private fun tieneCabeceraGguf(archivo: File): Boolean {
        if (!archivo.isFile || archivo.length() < 4L) return false
        FileInputStream(archivo).use { entrada ->
            val magic = ByteArray(4)
            val n = entrada.read(magic)
            return n == 4 &&
                magic[0] == 'G'.code.toByte() &&
                magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() &&
                magic[3] == 'F'.code.toByte()
        }
    }

    private fun sha256(archivo: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(archivo).use { entrada ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = entrada.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
