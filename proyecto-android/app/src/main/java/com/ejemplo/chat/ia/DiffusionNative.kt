package com.ejemplo.chat.ia

/**
 * Puente JNI del motor de imagen/vídeo.
 *
 * La biblioteca se genera en GitHub Actions y se empaqueta como
 * libchatpro-diffusion.so para arm64-v8a. No incluye pesos de modelos.
 */
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
                runCatching { DebugLog.log("DIFFUSION", "nativeVersion=${nativeVersion().take(500)}") }.onFailure { DebugLog.log("DIFFUSION", "⚠ nativeVersion falló: ${it.message}") }
            } catch (e: UnsatisfiedLinkError) {
                disponible = false
                DebugLog.log("DIFFUSION", "⚠ no se pudo cargar lib$LIBRARY_NAME.so: ${e.message}")
            } finally {
                cargaIntentada = true
            }
            return disponible
        }
    }

    fun version(): String {
        check(estaDisponible()) { "La biblioteca de difusión nativa no está incluida en este APK." }
        return nativeVersion()
    }

    /** Escribe un contenedor interno CPIMG1 con los píxeles de la primera imagen generada. */
    fun generarImagen(
        modelPath: String,
        clipLPath: String? = null,
        clipGPath: String? = null,
        vaePath: String? = null,
        prompt: String,
        negativePrompt: String = "",
        outputPath: String,
        width: Int = 512,
        height: Int = 512,
        steps: Int = 4,
        seed: Long = -1L
    ): Boolean {
        check(estaDisponible()) { "La biblioteca de difusión nativa no está incluida en este APK." }
        require(width > 0 && height > 0)
        require(steps > 0)
        return nativeGenerateImage(
            modelPath,
            clipLPath ?: "",
            clipGPath ?: "",
            vaePath ?: "",
            prompt,
            negativePrompt,
            outputPath,
            width,
            height,
            steps,
            seed
        )
    }

    /** Escribe un contenedor interno CPVID1 con frames RGBA para el encoder Android posterior. */
    fun generarVideo(
        diffusionModelPath: String,
        vaePath: String,
        t5xxlPath: String,
        prompt: String,
        negativePrompt: String = "",
        outputPath: String,
        width: Int = 416,
        height: Int = 240,
        frames: Int = 17,
        fps: Int = 8,
        steps: Int = 8,
        seed: Long = -1L
    ): Boolean {
        check(estaDisponible()) { "La biblioteca de difusión nativa no está incluida en este APK." }
        require(width > 0 && height > 0 && frames > 0 && fps > 0 && steps > 0)
        return nativeGenerateVideo(
            diffusionModelPath,
            vaePath,
            t5xxlPath,
            prompt,
            negativePrompt,
            outputPath,
            width,
            height,
            frames,
            fps,
            steps,
            seed
        )
    }

    data class Progreso(val fase: Int, val paso: Int, val pasos: Int, val ultimoPasoMs: Long)

    /** fase: 0 inactivo · 1 cargando modelo · 2 muestreando · 3 decodificando/guardando. */
    fun progreso(): Progreso {
        if (!estaDisponible()) return Progreso(0, 0, 0, 0)
        return try {
            val p = nativeProgress().split('|')
            Progreso(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toLong())
        } catch (_: Throwable) {
            Progreso(0, 0, 0, 0)
        }
    }

    private external fun nativeProgress(): String
    private external fun nativeVersion(): String
    private external fun nativeGenerateImage(
        modelPath: String,
        clipLPath: String,
        clipGPath: String,
        vaePath: String,
        prompt: String,
        negativePrompt: String,
        outputPath: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Long
    ): Boolean

    private external fun nativeGenerateVideo(
        diffusionModelPath: String,
        vaePath: String,
        t5xxlPath: String,
        prompt: String,
        negativePrompt: String,
        outputPath: String,
        width: Int,
        height: Int,
        frames: Int,
        fps: Int,
        steps: Int,
        seed: Long
    ): Boolean
}
