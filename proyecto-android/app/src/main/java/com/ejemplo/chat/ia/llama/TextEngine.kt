package com.ejemplo.chat.ia.llama

import com.ejemplo.chat.ia.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Contrato de motor de texto que usará la UI cuando el runtime esté disponible. */
interface TextEngine {
    val backendId: String
    val disponible: Boolean

    suspend fun cargar(modelo: File, contexto: Int = 4096, hilos: Int = 4)
    fun generar(prompt: String, maxTokens: Int = 2048): Flow<String>
    /** Pide parar la generación en curso (el texto generado hasta ese momento se conserva). */
    fun detener() {}
    suspend fun restaurarHistorial(historial: List<Pair<String, String>>): Boolean
    fun liberar()
}

/**
 * Implementación JNI de llama.cpp. En esta fase está deliberadamente aislada
 * para que el motor LiteRT actual siga siendo el fallback de producción.
 */
class NativeLlamaCppTextEngine : TextEngine {

    override val backendId: String = "llama.cpp-jni"
    override val disponible: Boolean
        get() = LlamaCppNative.estaDisponible()

    private var handle: Long = 0L
    @Volatile private var detenerPedido = false

    override fun detener() { detenerPedido = true }

    override suspend fun cargar(modelo: File, contexto: Int, hilos: Int) {
        require(modelo.isFile) { "El archivo del modelo no existe: ${modelo.absolutePath}" }
        check(disponible) {
            "llama.cpp nativo no está incluido todavía. Primero hay que incorporar su árbol C++/CMake al proyecto."
        }
        liberar()
        DebugLog.log("LLAMA", "cargando ${modelo.name} (${modelo.length() / 1_048_576} MB) · contexto=$contexto hilos=$hilos · ${DebugLog.mem()}")
        val inicio = System.currentTimeMillis()
        try {
            handle = withContext(Dispatchers.Default) {
                LlamaCppNative.cargar(modelo.absolutePath, contexto, hilos)
            }
        } catch (e: Throwable) {
            DebugLog.log("LLAMA", "⚠ excepción al cargar: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        val ms = System.currentTimeMillis() - inicio
        if (handle == 0L) {
            DebugLog.log("LLAMA", "⚠ la carga devolvió handle 0 tras $ms ms (GGUF no soportado o sin memoria) · ${DebugLog.mem()}")
        } else {
            DebugLog.log("LLAMA", "modelo cargado en $ms ms · ${DebugLog.mem()}")
            try { DebugLog.log("LLAMA", "CPU/ruta: ${LlamaCppNative.version().take(300)}") } catch (_: Throwable) {}
        }
        check(handle != 0L) { "llama.cpp no pudo cargar el modelo." }
    }

    /** Emite el texto por trozos según se genera (streaming). */
    override fun generar(prompt: String, maxTokens: Int): Flow<String> = channelFlow {
        check(handle != 0L) { "Carga un modelo antes de generar." }
        detenerPedido = false
        DebugLog.log("LLAMA", "generar: prompt=${prompt.length} car «${prompt.take(80).replace("\n", "\\n")}» · maxTokens=$maxTokens · ${DebugLog.mem()}")
        val inicio = System.currentTimeMillis()
        var trozos = 0
        var primero = 0L
        val canal = this
        val escucha = object : LlamaCppNative.TokenListener {
            override fun onToken(texto: String): Boolean {
                if (trozos == 0) primero = System.currentTimeMillis() - inicio
                trozos++
                canal.trySend(texto)
                return !detenerPedido
            }
        }
        val texto = try {
            withContext(Dispatchers.Default) { LlamaCppNative.generar(handle, prompt, maxTokens, escucha) }
        } catch (e: Throwable) {
            DebugLog.log("LLAMA", "⚠ excepción en generar: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        val ms = System.currentTimeMillis() - inicio
        // Mensajes que el motor devuelve sin generar (p. ej. "contexto lleno") no pasan por el callback.
        if (trozos == 0 && texto.isNotEmpty()) canal.trySend(texto)
        val segundos = (ms - primero).coerceAtLeast(1) / 1000.0
        val cola = if (texto.length > 260) " … ${texto.takeLast(80).replace("\n", "\\n")}" else ""
        DebugLog.log("LLAMA", "salida: ${texto.length} car en $ms ms (1.er trozo a los $primero ms · ${"%.1f".format(trozos / segundos)} trozos/s) · «${texto.take(180).replace("\n", "\\n")}»$cola")
        DebugLog.log("LLAMA", "nativo: ${LlamaCppNative.ultimoEstado()}")
        if (texto.isBlank()) DebugLog.log("LLAMA", "⚠ el motor devolvió una salida vacía")
    }.buffer(Channel.UNLIMITED)

    override suspend fun restaurarHistorial(historial: List<Pair<String, String>>): Boolean {
        check(handle != 0L) { "Carga un modelo antes de restaurar el historial." }
        val ok = withContext(Dispatchers.Default) { LlamaCppNative.restaurarHistorial(handle, historial) }
        DebugLog.log("LLAMA", "historial restaurado · mensajes=${historial.size} · ok=$ok · ${LlamaCppNative.ultimoEstado()}")
        return ok
    }

    override fun liberar() {
        val viejo = handle
        handle = 0L
        if (viejo != 0L) LlamaCppNative.liberar(viejo)
    }
}
