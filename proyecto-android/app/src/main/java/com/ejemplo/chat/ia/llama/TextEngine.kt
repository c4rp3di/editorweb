package com.ejemplo.chat.ia.llama

import com.ejemplo.chat.ia.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/** Contrato de motor de texto que usará la UI cuando el runtime esté disponible. */
interface TextEngine {
    val backendId: String
    val disponible: Boolean

    suspend fun cargar(modelo: File, contexto: Int = 4096, hilos: Int = 4)
    fun generar(prompt: String, maxTokens: Int = 256): Flow<String>
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

    override fun generar(prompt: String, maxTokens: Int): Flow<String> = flow {
        check(handle != 0L) { "Carga un modelo antes de generar." }
        DebugLog.log("LLAMA", "generar: prompt=${prompt.length} car «${prompt.take(80).replace("\n", "\\n")}» · maxTokens=$maxTokens · ${DebugLog.mem()}")
        val inicio = System.currentTimeMillis()
        val texto = try {
            withContext(Dispatchers.Default) {
                LlamaCppNative.generar(handle, prompt, maxTokens)
            }
        } catch (e: Throwable) {
            DebugLog.log("LLAMA", "⚠ excepción en generar: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        val ms = System.currentTimeMillis() - inicio
        val cola = if (texto.length > 260) " … ${texto.takeLast(80).replace("\n", "\\n")}" else ""
        DebugLog.log("LLAMA", "salida: ${texto.length} car en $ms ms · «${texto.take(180).replace("\n", "\\n")}»$cola")
        DebugLog.log("LLAMA", "nativo: ${LlamaCppNative.ultimoEstado()}")
        if (texto.isBlank()) DebugLog.log("LLAMA", "⚠ el motor devolvió una salida vacía")
        emit(texto)
    }

    override fun liberar() {
        val viejo = handle
        handle = 0L
        if (viejo != 0L) LlamaCppNative.liberar(viejo)
    }
}
