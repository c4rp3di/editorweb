package com.ejemplo.chat.ia.llama

import com.ejemplo.chat.ia.DebugLog

/**
 * Contrato JNI de Chat Pro para el runtime llama.cpp.
 *
 * La biblioteca "chatpro-llama" se construye desde el árbol oficial de llama.cpp incluido en este proyecto.
 */
object LlamaCppNative {

    private const val LIBRARY_NAME = "chatpro-llama"
    @Volatile private var cargaIntentada = false
    @Volatile private var disponible = false

    fun estaDisponible(): Boolean {
        if (cargaIntentada) return disponible
        synchronized(this) {
            if (cargaIntentada) return disponible
            try {
                System.loadLibrary(LIBRARY_NAME)
                disponible = true
                DebugLog.log("LLAMA", "lib$LIBRARY_NAME.so cargada correctamente")
            } catch (e: UnsatisfiedLinkError) {
                disponible = false
                DebugLog.log("LLAMA", "⚠ no se pudo cargar lib$LIBRARY_NAME.so: ${e.message}")
            } finally {
                cargaIntentada = true
            }
            return disponible
        }
    }

    fun version(): String = nativeVersion()

    fun cargar(rutaModelo: String, contexto: Int = 4096, hilos: Int = 4): Long {
        check(estaDisponible()) { "La biblioteca nativa llama.cpp todavía no está incluida en este APK." }
        require(contexto > 0) { "El contexto debe ser mayor que cero." }
        require(hilos > 0) { "El número de hilos debe ser mayor que cero." }
        return nativeLoadModel(rutaModelo, contexto, hilos)
    }

    fun generar(handle: Long, prompt: String, maxTokens: Int = 256): String {
        check(estaDisponible()) { "La biblioteca nativa llama.cpp todavía no está incluida en este APK." }
        require(handle != 0L) { "Handle de modelo inválido." }
        require(maxTokens > 0) { "maxTokens debe ser mayor que cero." }
        return nativeGenerate(handle, prompt, maxTokens)
    }

    /** Resumen del último generar() nativo (tokens, motivo de parada, tail del prompt). Solo para depuración. */
    fun ultimoEstado(): String = try {
        if (estaDisponible()) nativeLastStatus() else "(runtime no disponible)"
    } catch (e: UnsatisfiedLinkError) {
        "(estado nativo no disponible: ${e.message})"
    }

    fun liberar(handle: Long) {
        if (handle != 0L && estaDisponible()) nativeRelease(handle)
    }

    private external fun nativeVersion(): String
    private external fun nativeLoadModel(rutaModelo: String, contexto: Int, hilos: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeRelease(handle: Long)
    private external fun nativeLastStatus(): String
}
