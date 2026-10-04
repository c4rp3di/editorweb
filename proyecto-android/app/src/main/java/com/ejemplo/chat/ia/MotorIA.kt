package com.ejemplo.chat.ia

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// Motor de IA local (LiteRT-LM). Descarga un modelo .litertlm a filesDir la
// primera vez y luego responde sin conexion. Usa MotorIA desde tu propia UI:
//   val motor = MotorIA(this)
//   val m = MotorIA.MODELOS[0]
//   lifecycleScope.launch {
//       if (!motor.modeloDescargado(m)) motor.descargarModelo(m) { p -> /* 0..100 */ }
//       motor.inicializar(m)
//       motor.generar("Hola").collect { trozo -> /* texto en streaming */ }
//   }
class MotorIA(private val context: Context) {

    data class Modelo(val id: String, val nombre: String, val url: String, val archivo: String, val tamanoMb: Int)

    enum class Estado { NO_DESCARGADO, DESCARGANDO, LISTO, ERROR }

    companion object {
        val MODELOS = listOf(
            Modelo("qwen3-0.6b", "Qwen3 0.6B (ligero)",
                "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                "Qwen3-0.6B.litertlm", 590),
            Modelo("gemma4-e2b", "Gemma 4 E2B (equilibrado)",
                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                "gemma-4-E2B-it.litertlm", 2600),
            Modelo("gemma4-e4b", "Gemma 4 E4B (mejor calidad, pesado)",
                "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                "gemma-4-E4B-it.litertlm", 3700)
        )
    }

    var estado: Estado = Estado.NO_DESCARGADO
        private set

    private var engine: Engine? = null
    private var conversacion: Conversation? = null

    private fun carpeta(): File = File(context.filesDir, "modelos-ia").apply { mkdirs() }
    private fun archivoDe(m: Modelo): File = File(carpeta(), m.archivo)

    fun modeloDescargado(m: Modelo): Boolean = archivoDe(m).exists()

    fun espacioLibreMb(): Long = carpeta().usableSpace / (1024 * 1024)

    private fun hayRed(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Descarga con reanudacion (archivo .part + cabecera Range).
    // onProgreso (0..100) se llama en el hilo principal.
    suspend fun descargarModelo(m: Modelo, onProgreso: (Int) -> Unit) {
        if (modeloDescargado(m)) { estado = Estado.LISTO; return }
        if (!hayRed()) { estado = Estado.ERROR; throw IllegalStateException("Sin conexion para descargar el modelo") }
        if (espacioLibreMb() < m.tamanoMb + 200) {
            estado = Estado.ERROR
            throw IllegalStateException("Espacio insuficiente: hacen falta unos " + (m.tamanoMb + 200) + " MB libres")
        }
        estado = Estado.DESCARGANDO
        try {
            withContext(Dispatchers.IO) {
                val destino = archivoDe(m)
                val parcial = File(carpeta(), m.archivo + ".part")
                val yaBajado = if (parcial.exists()) parcial.length() else 0L
                val con = URL(m.url).openConnection() as HttpURLConnection
                con.connectTimeout = 15000
                con.readTimeout = 30000
                con.instanceFollowRedirects = true
                if (yaBajado > 0) con.setRequestProperty("Range", "bytes=" + yaBajado + "-")
                val codigo = con.responseCode
                if (codigo == 416 && parcial.exists()) {
                    parcial.renameTo(destino)
                    return@withContext
                }
                if (codigo != 200 && codigo != 206) throw IllegalStateException("Descarga rechazada (HTTP " + codigo + ")")
                val reanuda = codigo == 206
                var leidos = if (reanuda) yaBajado else 0L
                val total = leidos + con.contentLengthLong
                var ultimo = -1
                con.inputStream.use { entrada ->
                    FileOutputStream(parcial, reanuda).use { salida ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val n = entrada.read(buf)
                            if (n < 0) break
                            salida.write(buf, 0, n)
                            leidos += n
                            ensureActive()
                            if (total > 0) {
                                val p = (leidos * 100 / total).toInt()
                                if (p != ultimo) {
                                    ultimo = p
                                    withContext(Dispatchers.Main) { onProgreso(p) }
                                }
                            }
                        }
                    }
                }
                if (!parcial.renameTo(destino)) throw IllegalStateException("No se pudo guardar el modelo")
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

    // Carga el modelo (puede tardar unos segundos). Intenta GPU y cae a CPU.
    suspend fun inicializar(m: Modelo) {
        withContext(Dispatchers.IO) {
            liberar()
            val ruta = archivoDe(m).absolutePath
            val cache = context.cacheDir.path
            val nuevo = try {
                Engine(EngineConfig(modelPath = ruta, backend = Backend.GPU(), cacheDir = cache)).also { it.initialize() }
            } catch (e: Exception) {
                Engine(EngineConfig(modelPath = ruta, backend = Backend.CPU(), cacheDir = cache)).also { it.initialize() }
            }
            engine = nuevo
            conversacion = nuevo.createConversation()
        }
    }

    // Respuesta en streaming: cada elemento del Flow es un trozo de texto.
    fun generar(prompt: String): Flow<String> {
        val c = conversacion ?: throw IllegalStateException("Llama a inicializar() antes de generar()")
        return c.sendMessageAsync(prompt).map { it.toString() }
    }

    fun liberar() {
        try { conversacion?.close() } catch (e: Exception) { }
        try { engine?.close() } catch (e: Exception) { }
        conversacion = null
        engine = null
    }
}
