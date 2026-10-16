package com.ejemplo.chat.ia

object DiffusionNative {
    private const val LIBRARY_NAME = "chatpro-diffusion"
    @Volatile private var cargaIntentada = false
    @Volatile private var disponible = false

    fun estaDisponible(): Boolean {
        if (cargaIntentada) return disponible
        synchronized(this) {
            if (cargaIntentada) return disponible
            try {
                System.loadLibrary(LIBRARY_NAME)
                disponible = true
                DebugLog.log("DIFFUSION", "lib$LIBRARY_NAME.so cargada correctamente")
                DebugLog.nativeLogPath()?.let { ruta -> runCatching { nativeInstallLog(ruta) }.onFailure { DebugLog.log("DIFFUSION", "⚠ no se pudo activar el registro nativo: ${it.message}") } }
            } catch (e: UnsatisfiedLinkError) {
                disponible = false
                DebugLog.log("DIFFUSION", "⚠ no se pudo cargar lib$LIBRARY_NAME.so: ${e.message}")
            } finally {
                cargaIntentada = true
            }
            return disponible
        }
    }

    fun generarImagen(
        modelPath: String,
        prompt: String,
        negativePrompt: String = "",
        outputPath: String,
        width: Int = 512,
        height: Int = 512,
        steps: Int = 25,
        seed: Long = -1L
    ): Boolean {
        check(estaDisponible()) { "La biblioteca de difusión nativa no está incluida en este APK." }
        require(width > 0 && height > 0)
        require(steps > 0)
        return nativeGenerateImage(modelPath, "", "", "", prompt, negativePrompt, outputPath, width, height, steps, seed)
    }

    data class Progreso(val fase: Int, val paso: Int, val pasos: Int, val ultimoPasoMs: Long)

    fun progreso(): Progreso {
        if (!estaDisponible()) return Progreso(0, 0, 0, 0)
        return try {
            val p = nativeProgress().split('|')
            Progreso(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toLong())
        } catch (_: Throwable) { Progreso(0, 0, 0, 0) }
    }

    private external fun nativeProgress(): String
    private external fun nativeInstallLog(path: String)
    private external fun nativeGenerateImage(
        modelPath: String, clipLPath: String, clipGPath: String, vaePath: String,
        prompt: String, negativePrompt: String, outputPath: String,
        width: Int, height: Int, steps: Int, seed: Long
    ): Boolean
}
