package com.ejemplo.chat.ia

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
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

    data class Modelo(val id: String, val nombre: String, val url: String, val archivo: String, val tamanoMb: Int, val vision: Boolean = false)

    enum class Estado { NO_DESCARGADO, DESCARGANDO, LISTO, ERROR }

    companion object {
        val MODELOS = listOf(
            Modelo(
                "qwen3-0.6b", "Qwen3 0.6B", 
                "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
                "Qwen3-0.6B.litertlm", 590, false
            ),
            Modelo(
                "smollm2-360m", "SmolLM2 360M",
                "https://huggingface.co/litert-community/SmolLM2-360M-Instruct/resolve/main/SmolLM2_360M_instruct.litertlm",
                "SmolLM2_360M_instruct.litertlm", 374, false
            ),
            Modelo(
                "qwen25-1.5b", "Qwen 2.5 1.5B",
                "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
                "Qwen2.5-1.5B-Instruct.litertlm", 1600, false
            ),
            Modelo(
                "gemma4-e2b", "Gemma 4 E2B",
                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
                "gemma-4-E2B-it.litertlm", 2600, true
            ),
            Modelo(
                "gemma4-e4b", "Gemma 4 E4B",
                "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
                "gemma-4-E4B-it.litertlm", 3700, true
            )
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

    private fun config(ruta: String, cache: String, gpu: Boolean, vision: Boolean): EngineConfig {
        val fondo = if (gpu) Backend.GPU() else Backend.CPU()
        return if (vision) EngineConfig(modelPath = ruta, backend = fondo, visionBackend = fondo, cacheDir = cache)
        else EngineConfig(modelPath = ruta, backend = fondo, cacheDir = cache)
    }

    // historial: lista de pares (rol, texto) con rol "u" (usuario) o "m" (modelo).
    private fun crearConversacion(e: Engine, historial: List<Pair<String, String>>): Conversation {
        if (historial.isEmpty()) return e.createConversation()
        val iniciales = historial.takeLast(20).map {
            if (it.first == "u") Message.user(it.second) else Message.model(it.second)
        }
        return e.createConversation(ConversationConfig(initialMessages = iniciales))
    }

    // Carga el modelo (puede tardar unos segundos). Intenta GPU y cae a CPU.
    suspend fun inicializar(m: Modelo, historial: List<Pair<String, String>> = emptyList()) {
        withContext(Dispatchers.IO) {
            liberar()
            val ruta = archivoDe(m).absolutePath
            val cache = context.cacheDir.path
            val nuevo = try {
                Engine(config(ruta, cache, true, m.vision)).also { it.initialize() }
            } catch (e: Exception) {
                Engine(config(ruta, cache, false, m.vision)).also { it.initialize() }
            }
            engine = nuevo
            conversacion = crearConversacion(nuevo, historial)
        }
    }

    fun nuevaConversacion(historial: List<Pair<String, String>> = emptyList()) {
        val e = engine ?: return
        try { conversacion?.close() } catch (ex: Exception) { }
        conversacion = crearConversacion(e, historial)
    }

    // Respuesta en streaming. rutaImagen solo funciona con modelos con vision = true.
    fun generar(prompt: String, rutaImagen: String? = null): Flow<String> {
        val c = conversacion ?: throw IllegalStateException("Llama a inicializar() antes de generar()")
        val flujo = if (rutaImagen != null) {
            c.sendMessageAsync(Contents.of(Content.ImageFile(rutaImagen), Content.Text(prompt)))
        } else {
            c.sendMessageAsync(prompt)
        }
        return flujo.map { it.toString() }
    }

    fun liberar() {
        try { conversacion?.close() } catch (e: Exception) { }
        try { engine?.close() } catch (e: Exception) { }
        conversacion = null
        engine = null
    }
}
