package com.carpe.microlisto.transcripcion

import android.content.Context
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * Wrapper de Vosk. El modelo viene empaquetado en assets/vosk-model-small-es-0.42
 * y se copia a la carpeta interna de la app la primera vez que se usa (39 MB,
 * tarda unos segundos).
 *
 * Acepta buffers de 512 samples float [-1..1] a 16 kHz. Internamente los
 * convierte a ShortArray que es lo que Vosk espera.
 */
class Transcriber(
    private val context: Context,
    private val onListo: () -> Unit = {},
    private val onParcial: (String) -> Unit = {},
    private val onFinal: (String) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var listo = false

    fun iniciar() {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            StorageService.unpack(
                context,
                "vosk-model-small-es-0.42",
                "modelo",
                { modelDesempaquetado ->
                    model = modelDesempaquetado
                    try {
                        recognizer = Recognizer(modelDesempaquetado, 16000.0f)
                        listo = true
                        onListo()
                    } catch (e: Exception) {
                        onError("Error al crear el recognizer: ${e.message}")
                    }
                },
                { error ->
                    onError("Error al desempaquetar el modelo: ${error.message}")
                }
            )
        } catch (e: Exception) {
            onError("Error al iniciar Vosk: ${e.message}")
        }
    }

    /**
     * Recibe un frame de 512 samples float. Los convierte a ShortArray y los
     * pasa al recognizer. Emite resultados parciales y finales por los callbacks.
     */
    fun aceptarFrame(frame512: FloatArray, cantidad: Int) {
        val rec = recognizer ?: return
        if (!listo) return
        val shorts = ShortArray(cantidad)
        var i = 0
        while (i < cantidad) {
            var s = frame512[i]
            if (s > 1f) s = 1f
            if (s < -1f) s = -1f
            shorts[i] = (s * 32767f).toInt().toShort()
            i++
        }
        try {
            if (rec.acceptWaveForm(shorts, shorts.size)) {
                val final = rec.result
                if (final != null && final.isNotBlank()) {
                    onFinal(final)
                }
            } else {
                val parcial = rec.partialResult
                if (parcial != null && parcial.isNotBlank()) {
                    onParcial(parcial)
                }
            }
        } catch (_: Exception) {}
    }

    fun cerrar() {
        try {
            recognizer?.close()
        } catch (_: Exception) {}
        recognizer = null
        try {
            model?.close()
        } catch (_: Exception) {}
        model = null
        listo = false
    }
}