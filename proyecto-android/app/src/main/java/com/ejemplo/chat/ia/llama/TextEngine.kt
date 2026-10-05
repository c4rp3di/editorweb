package com.ejemplo.chat.ia.llama

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
        handle = withContext(Dispatchers.Default) {
            LlamaCppNative.cargar(modelo.absolutePath, contexto, hilos)
        }
        check(handle != 0L) { "llama.cpp no pudo cargar el modelo." }
    }

    override fun generar(prompt: String, maxTokens: Int): Flow<String> = flow {
        check(handle != 0L) { "Carga un modelo antes de generar." }
        val texto = withContext(Dispatchers.Default) {
            LlamaCppNative.generar(handle, prompt, maxTokens)
        }
        emit(texto)
    }

    override fun liberar() {
        val viejo = handle
        handle = 0L
        if (viejo != 0L) LlamaCppNative.liberar(viejo)
    }
}
