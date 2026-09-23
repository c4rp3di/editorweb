package com.carpe.microlisto.transcripcion

import android.content.Context
import com.carpe.microlisto.debug.DebugLog
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Wrapper de Vosk. El modelo se carga desde una ruta externa
 * (/storage/emulated/0/Microlisto/vosk/vosk-model-es-0.42/) en lugar de
 * desde assets. Si el modelo no está disponible, la app graba WAV sin
 * transcribir, y el usuario puede reprocesar más adelante.
 */
class Transcriber(
    private val context: Context,
    private val rutaModelo: String,
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
            DebugLog.info("Transcriber", "Cargando modelo desde $rutaModelo")

            val modelCargado = Model(rutaModelo)
            model = modelCargado
            recognizer = Recognizer(modelCargado, 16000.0f)
            listo = true
            onListo()
            DebugLog.info("Transcriber", "Modelo Vosk cargado, recognizer listo")
        } catch (e: Exception) {
            DebugLog.error("Transcriber", "Error al cargar modelo: ${e.message}")
            onError("Error al cargar modelo Vosk: ${e.message}")
        }
    }

    fun estaListo(): Boolean = listo

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
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { model?.close() } catch (_: Exception) {}
        model = null
        listo = false
    }
}