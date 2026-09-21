package com.carpe.microlisto.vad

/**
 * Convierte una secuencia de probabilidades de voz (una por frame de 32 ms)
 * en eventos de "inicio de voz" y "fin de voz".
 *
 * Reglas:
 * - Un frame se considera voz si probabilidad >= UMBRAL_VOZ.
 * - Un frame se considera silencio si probabilidad < UMBRAL_SILENCIO.
 * - Para abrir un segmento hacen falta MIN_FRAMES_VOZ consecutivos de voz.
 * - Para cerrarlo hacen falta MIN_FRAMES_SILENCIO consecutivos de silencio.
 * - Entre medias (entre ambos umbrales), se mantiene el estado anterior. Esto
 *   evita que un pequeño ruido cierre el segmento antes de tiempo.
 *
 * Con frames de 512 samples a 16 kHz, cada frame son 32 ms. Por tanto:
 *   MIN_FRAMES_VOZ = 8  -> 256 ms de voz para abrir
 *   MIN_FRAMES_SILENCIO = 10 -> 320 ms de silencio para cerrar
 */
class VadSegmenter(
    private val umbralVoz: Float = 0.5f,
    private val umbralSilencio: Float = 0.35f,
    private val minFramesVoz: Int = 8,
    private val minFramesSilencio: Int = 10
) {
    private var framesVozConsecutivos = 0
    private var framesSilencioConsecutivos = 0
    private var hablando = false

    /**
     * @return true si este frame abre un nuevo segmento de voz (transición silencio -> voz)
     *         false en cualquier otro caso.
     */
    fun actualizar(probabilidad: Float): Boolean {
        val eraVoz = probabilidad >= umbralVoz
        val eraSilencio = probabilidad < umbralSilencio

        if (!hablando) {
            if (eraVoz) {
                framesVozConsecutivos++
                if (framesVozConsecutivos >= minFramesVoz) {
                    hablando = true
                    framesVozConsecutivos = 0
                    framesSilencioConsecutivos = 0
                    return true
                }
            } else if (eraSilencio) {
                framesVozConsecutivos = 0
            }
        } else {
            if (eraSilencio) {
                framesSilencioConsecutivos++
                if (framesSilencioConsecutivos >= minFramesSilencio) {
                    hablando = false
                    framesVozConsecutivos = 0
                    framesSilencioConsecutivos = 0
                }
            } else if (eraVoz) {
                framesSilencioConsecutivos = 0
            }
        }
        return false
    }

    fun estaHablando(): Boolean = hablando

    fun reset() {
        framesVozConsecutivos = 0
        framesSilencioConsecutivos = 0
        hablando = false
    }
}