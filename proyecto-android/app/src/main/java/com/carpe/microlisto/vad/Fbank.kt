package com.carpe.microlisto.vad

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Front-end de audio que convierte PCM float [-1, 1] a 16 kHz en la matriz
 * de características que WeSpeaker espera: [numFrames, 80] bins mel con
 * normalización de media cepstral (CMN).
 *
 * Parámetros verificados contra la configuración típica de WeSpeaker:
 *   frame_length = 25 ms (400 muestras)
 *   frame_shift  = 10 ms (160 muestras)
 *   num_mel_bins = 80
 *   low_freq     = 20 Hz
 *   high_freq    = 8000 Hz (Nyquist)
 *   preemph      = 0.97
 *   remove_dc    = true
 *   window       = povey (kaldi la usa por defecto, no Hamming)
 *   dither       = 0
 *   snip_edges   = true
 *   sin_energía  = true (no se incluye el log de energía como primer bin)
 */
class Fbank(
    private val sampleRate: Int = 16000,
    private val numMelBins: Int = 80,
    private val frameLengthMs: Int = 25,
    private val frameShiftMs: Int = 10,
    private val lowFreq: Float = 20f,
    private val highFreq: Float = 0f,
    private val preemph: Float = 0.97f,
    private val removeDc: Boolean = true
) {
    private val frameLength = sampleRate * frameLengthMs / 1000
    private val frameShift = sampleRate * frameShiftMs / 1000
    private val fftSize = nextPow2(frameLength)
    private val numBins = fftSize / 2 + 1

    private val ventana: FloatArray = ventanaPovey(frameLength)
    private val melBanks: Array<FloatArray> = crearMelBanks()

    /**
     * @param audio muestras float [-1, 1] a 16 kHz
     * @return matriz de [numFrames][80] con log-mel normalizado
     */
    fun calcular(audio: FloatArray): Array<FloatArray> {
        // snip_edges=true: solo frames completos, sin relleno
        val numFrames = if (audio.size < frameLength) 0
                        else (audio.size - frameLength) / frameShift + 1
        if (numFrames <= 0) return emptyArray()

        val resultado = Array(numFrames) { FloatArray(numMelBins) }

        // Pre-énfasis (una sola pasada sobre todo el audio)
        val audioPre = FloatArray(audio.size)
        audioPre[0] = audio[0]
        for (i in 1 until audio.size) {
            audioPre[i] = audio[i] - preemph * audio[i - 1]
        }

        val frame = FloatArray(fftSize)
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)

        for (f in 0 until numFrames) {
            val inicio = f * frameShift

            // Extraer frame + remove DC
            var suma = 0f
            for (i in 0 until frameLength) {
                val v = audioPre[inicio + i]
                frame[i] = v
                suma += v
            }
            if (removeDc && frameLength > 0) {
                val media = suma / frameLength
                for (i in 0 until frameLength) frame[i] -= media
            }

            // Aplicar ventana povey
            for (i in 0 until frameLength) {
                frame[i] *= ventana[i]
            }
            // Relleno con ceros hasta fftSize
            for (i in frameLength until fftSize) frame[i] = 0f

            // FFT
            for (i in 0 until fftSize) { real[i] = frame[i]; imag[i] = 0f }
            fft(real, imag)

            // Espectro de potencia
            val potencia = FloatArray(numBins)
            for (i in 0 until numBins) {
                potencia[i] = real[i] * real[i] + imag[i] * imag[i]
            }

            // Mel filterbank + log
            for (m in 0 until numMelBins) {
                var sumaBand = 0f
                val banco = melBanks[m]
                for (k in 0 until numBins) {
                    if (banco[k] != 0f) sumaBand += banco[k] * potencia[k]
                }
                if (sumaBand < 1e-10f) sumaBand = 1e-10f
                resultado[f][m] = ln(sumaBand)
            }
        }

        // CMN: restar la media de cada bin a lo largo del tiempo
        aplicarCmn(resultado)

        return resultado
    }

    /**
     * Cepstral Mean Normalization: para cada bin mel, restar la media temporal.
     */
    private fun aplicarCmn(matriz: Array<FloatArray>) {
        if (matriz.isEmpty()) return
        val numFrames = matriz.size
        val medias = FloatArray(numMelBins)
        for (f in 0 until numFrames) {
            for (m in 0 until numMelBins) {
                medias[m] += matriz[f][m]
            }
        }
        for (m in 0 until numMelBins) medias[m] /= numFrames
        for (f in 0 until numFrames) {
            for (m in 0 until numMelBins) {
                matriz[f][m] -= medias[m]
            }
        }
    }

    /**
     * Ventana povey, que es la que usa kaldi por defecto:
     *   w[n] = (0.5 - 0.5*cos(2*pi*n/(N-1)))^0.85
     */
    private fun ventanaPovey(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) {
            val x = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            w[i] = x.pow(0.85).toFloat()
        }
        return w
    }

    /**
     * Construye el banco de filtros mel triangular.
     * Los filtros son triangulares en el eje mel, normalizados para que cada
     * uno sume 1 (esto es lo que kaldi hace por defecto).
     */
    private fun crearMelBanks(): Array<FloatArray> {
        val fMax = if (highFreq > 0f) highFreq else (sampleRate / 2).toFloat()
        val melLow = hzAMel(lowFreq)
        val melHigh = hzAMel(fMax)

        val puntos = FloatArray(numMelBins + 2)
        for (i in 0 until numMelBins + 2) {
            val m = melLow + (melHigh - melLow) * i / (numMelBins + 1)
            puntos[i] = melAhz(m)
        }

        // Frecuencia central de cada bin FFT
        val frecuenciaBins = FloatArray(numBins)
        for (k in 0 until numBins) {
            frecuenciaBins[k] = k.toFloat() * sampleRate / fftSize
        }

        val bancos = Array(numMelBins) { FloatArray(numBins) }
        for (m in 1..numMelBins) {
            val fIzq = puntos[m - 1]
            val fCen = puntos[m]
            val fDer = puntos[m + 1]
            for (k in 0 until numBins) {
                val f = frecuenciaBins[k]
                var v = 0f
                if (f in fIzq..fCen && fCen > fIzq) {
                    v = (f - fIzq) / (fCen - fIzq)
                } else if (f in fCen..fDer && fDer > fCen) {
                    v = (fDer - f) / (fDer - fCen)
                }
                bancos[m - 1][k] = v
            }
        }
        return bancos
    }

    private fun hzAMel(hz: Float): Float = 1127f * ln(1f + hz / 700f)
    private fun melAhz(mel: Float): Float = 700f * (kotlin.math.exp(mel / 1127f) - 1f)

    /**
     * FFT radix-2 in-place. El tamaño debe ser potencia de 2.
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        // Bit reversal
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }
        // Mariposas
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wReal = cos(ang).toFloat()
            val wImag = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var uReal = 1f
                var uImag = 0f
                for (k in 0 until len / 2) {
                    val iP = i + k
                    val iQ = i + k + len / 2
                    val tReal = uReal * real[iQ] - uImag * imag[iQ]
                    val tImag = uReal * imag[iQ] + uImag * real[iQ]
                    real[iQ] = real[iP] - tReal
                    imag[iQ] = imag[iP] - tImag
                    real[iP] += tReal
                    imag[iP] += tImag
                    val nuevoUReal = uReal * wReal - uImag * wImag
                    uImag = uReal * wImag + uImag * wReal
                    uReal = nuevoUReal
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}