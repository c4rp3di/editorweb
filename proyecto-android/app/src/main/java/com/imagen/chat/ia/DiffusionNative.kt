package com.imagen.chat.ia

/**
 * Puente JNI del motor de imagen (stable-diffusion.cpp).
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
                DebugLog.nativeLogPath()?.let { ruta ->
                    runCatching { nativeInstallLog(ruta) }
                        .onFailure { DebugLog.log("DIFFUSION", "⚠ no se pudo activar el registro nativo: ${it.message}") }
                }
                runCatching { DebugLog.log("DIFFUSION", "nativeVersion=${nativeVersion().take(500)}") }
                    .onFailure { DebugLog.log("DIFFUSION", "⚠ nativeVersion falló: ${it.message}") }
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

    /**
     * Genera una imagen y la escribe directamente como PNG nativo.
     * @param cfg escala de guiado (guidance). 7.0 es el valor típico. Con prompt negativo vacío se fuerza 1.0.
     * @param sharpness 0.0 = sin post-proceso; 0.8 = nitidez alta. Se aplica unsharp mask en el pipeline nativo.
     * @param sampler 0 = EULER_A + KARRAS (mejor calidad), 1 = EULER + DISCRETE, 2 = LCM, 3 = EULER_A + DISCRETE (2 y 3: modelos LCM, 4-8 pasos, CFG 1-2).
     * @param vaeTile tamaño de mosaico del VAE en px (64-1024). Mayor = más RAM punta y menos tiempo; si cubre la imagen entera no hay mosaicos.
     * @param flashAttn atención flash de ggml; desactivarla puede ir mejor en algunas GPU móviles.
     * @param mantenerCargado true = conserva el modelo en memoria al terminar y lo reutiliza si la
     *        siguiente imagen usa el mismo modelo (evita releer y convertir ~2-4 GB de pesos cada vez).
     */
    fun generarImagen(
        modelPath: String,
        vaePath: String?,
        prompt: String,
        negativePrompt: String,
        outputPath: String,
        width: Int = 512,
        height: Int = 512,
        steps: Int = 30,
        seed: Long = -1L,
        cfg: Float = 7.0f,
        sharpness: Float = 0.8f,
        sampler: Int = 0,
        mantenerCargado: Boolean = false,
        flashAttn: Boolean = true,
        vaeTile: Int = 192
    ): Boolean {
        check(estaDisponible()) { "La biblioteca de difusión nativa no está incluida en este APK." }
        require(width > 0 && height > 0 && steps > 0)
        require(width % 64 == 0 && height % 64 == 0) { "Ancho y alto deben ser múltiplos de 64 (SD 1.5)" }
        require(cfg in 1.0f..20.0f) { "CFG debe estar entre 1.0 y 20.0" }
        require(sharpness in 0.0f..2.0f) { "Sharpness debe estar entre 0.0 y 2.0" }
        return nativeGenerateImage(
            modelPath,
            vaePath ?: "",
            prompt,
            negativePrompt,
            outputPath,
            width,
            height,
            steps,
            seed,
            cfg,
            sharpness,
            sampler,
            mantenerCargado,
            flashAttn,
            vaeTile
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
    private external fun nativeInstallLog(path: String)
    private external fun nativeVersion(): String
    private external fun nativeGenerateImage(
        modelPath: String,
        vaePath: String,
        prompt: String,
        negativePrompt: String,
        outputPath: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Long,
        cfg: Float,
        sharpness: Float,
        sampler: Int,
        keepLoaded: Boolean,
        flashAttn: Boolean,
        vaeTile: Int
    ): Boolean
}
