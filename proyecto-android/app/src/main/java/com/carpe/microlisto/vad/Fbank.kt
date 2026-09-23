package com.carpe.microlisto.vad

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Front-end de audio que replica el comportamiento de kaldi.fbank con
 * window_type=hamming, tal como lo usa WeSpeaker para los embeddings.
 *
 * Orden de operaciones por frame (kaldi ProcessWindow):
 *   1. Extraer frame del audio
 *   2. Escalar a rango int16 (×32768)
 *   3. Remove DC offset (restar la media del frame)
 *   4. Preénfasis (x[n] -= 0.97 * x[n-1], con x[0] -= 0.97 * x[0])
 *   5. Aplicar ventana Hamming
 *   6. FFT
 *   7. Espectro de potencia
 *   8. Mel filterbanks (triángulos lineales en escala Mel)
 *   9. log
 *
 * Este Fbank NO hace CMN. El CMN se aplica por fragmento en el Diarizer,
 * como hace WeSpeaker con subseg_cmn=true.
 */
class Fbank(
    private val sampleRate: Int = 16000,
    private val numMelBins: Int = 80,
    private val frameLengthMs: Int = 25,
    private val frameShiftMs: Int = 10,
    private val lowFreq: Float = 20f,
    private val highFreq: Float = 0f,
    private val preemph: Float = 0.97f,
    private val removeDc: Boolean = true,
    private val escalaEntrada: Float = 32768f
) {
    private val frameLength = sampleRate * frameLengthMs / 1000
    private val frameShift = sampleRate * frameShiftMs / 1000
    private val fftSize = nextPow2(frameLength)

    // Kaldi usa los bins 0..fftSize/2-1, excluyendo el de Nyquist
    private val numFftBins = fftSize / 2
    // El vector de potencia tiene numFftBins+1 posiciones (incluye el Nyquist
    // para la FFT), pero al calcular mel solo se usan las primeras numFftBins.
    private val numBinsPotencia = fftSize / 2 + 1

    private val ventana: FloatArray = ventanaHamming(frameLength)
    private val melBanks: Array<FloatArray> = crearMelBanks()

    fun calcular(audio: FloatArray): Array<FloatArray> {
        val numFrames = if (audio.size < frameLength) 0
                        else (audio.size - frameLength) / frameShift + 1
        if (numFrames <= 0) return emptyArray()

        val resultado: Array<FloatArray> = Array(numFrames) { FloatArray(numMelBins) }

        val frame = FloatArray(fftSize)
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)

        var f = 0
        while (f < numFrames) {
            val inicio = f * frameShift

            // 1-2. Extraer frame y escalar a rango int16
            var suma = 0f
            var i = 0
            while (i < frameLength) {
                val v = audio[inicio + i] * escalaEntrada
                frame[i] = v
                suma += v
                i++
            }

            // 3. Remove DC offset (restar la media del frame)
            if (removeDc && frameLength > 0) {
                val media = suma / frameLength
                var j = 0
                while (j < frameLength) {
                    frame[j] -= media
                    j++
                }
            }

            // 4. Preénfasis por frame
            if (preemph != 0f && frameLength > 0) {
                var j = frameLength - 1
                while (j > 0) {
                    frame[j] -= preemph * frame[j - 1]
                    j--
                }
                frame[0] -= preemph * frame[0]
            }

            // 5. Ventana Hamming
            var j2 = 0
            while (j2 < frameLength) {
                frame[j2] *= ventana[j2]
                j2++
            }
            // Relleno con ceros hasta fftSize
            var j3 = frameLength
            while (j3 < fftSize) {
                frame[j3] = 0f
                j3++
            }

            // 6. FFT
            var j4 = 0
            while (j4 < fftSize) {
                real[j4] = frame[j4]
                imag[j4] = 0f
                j4++
            }
            fft(real, imag)

            // 7. Espectro de potencia
            val potencia = FloatArray(numBinsPotencia)
            var k = 0
            while (k < numBinsPotencia) {
                potencia[k] = real[k] * real[k] + imag[k] * imag[k]
                k++
            }

            // 8. Mel filterbanks + log
            var m = 0
            while (m < numMelBins) {
                var sumaBand = 0f
                val banco = melBanks[m]
                var kk = 0
                while (kk < numFftBins) {
                    if (banco[kk] != 0f) sumaBand += banco[kk] * potencia[kk]
                    kk++
                }
                if (sumaBand < 1e-10f) sumaBand = 1e-10f
                resultado[f][m] = ln(sumaBand)
                m++
            }
            f++
        }

        return resultado
    }

    private fun ventanaHamming(n: Int): FloatArray {
        val w = FloatArray(n)
        var i = 0
        while (i < n) {
            val x = 0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))
            w[i] = x.toFloat()
            i++
        }
        return w
    }

    /**
     * Construye el banco de filtros mel triangulares en el dominio Mel,
     * replicando mel-computations.cc de kaldi.
     *
     * A diferencia de una implementación lineal en Hz, kaldi convierte
     * cada frecuencia FFT a escala Mel y calcula los pesos triangulares
     * directamente en Mel.
     */
    private fun crearMelBanks(): Array<FloatArray> {
        val fMax = if (highFreq > 0f) highFreq else (sampleRate / 2).toFloat()
        val melLow = hzAMel(lowFreq)
        val melHigh = hzAMel(fMax)

        // Puntos centrales en dominio Mel
        val puntosMel = FloatArray(numMelBins + 2)
        var i = 0
        while (i < numMelBins + 2) {
            puntosMel[i] = melLow + (melHigh - melLow) * i / (numMelBins + 1)
            i++
        }

        val bancos: Array<FloatArray> = Array(numMelBins) { FloatArray(numFftBins) }

        var m = 1
        while (m <= numMelBins) {
            val leftMel = puntosMel[m - 1]
            val centerMel = puntosMel[m]
            val rightMel = puntosMel[m + 1]

            var k = 0
            while (k < numFftBins) {
                val freq = k.toFloat() * sampleRate / fftSize
                val mel = hzAMel(freq)

                var v = 0f
                if (mel > leftMel && mel <= centerMel && centerMel > leftMel) {
                    v = (mel - leftMel) / (centerMel - leftMel)
                } else if (mel > centerMel && mel < rightMel && rightMel > centerMel) {
                    v = (rightMel - mel) / (rightMel - centerMel)
                }
                bancos[m - 1][k] = v
                k++
            }
            m++
        }
        return bancos
    }

    private fun hzAMel(hz: Float): Float = 1127f * ln(1f + hz / 700f)

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        var i = 0
        while (i < n - 1) {
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
            i++
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wReal = cos(ang).toFloat()
            val wImag = sin(ang).toFloat()
            i = 0
            while (i < n) {
                var uReal = 1f
                var uImag = 0f
                var kk = 0
                while (kk < len / 2) {
                    val iP = i + kk
                    val iQ = i + kk + len / 2
                    val tReal = uReal * real[iQ] - uImag * imag[iQ]
                    val tImag = uReal * imag[iQ] + uImag * real[iQ]
                    real[iQ] = real[iP] - tReal
                    imag[iQ] = imag[iP] - tImag
                    real[iP] = real[iP] + tReal
                    imag[iP] = imag[iP] + tImag
                    val nuevoUReal = uReal * wReal - uImag * wImag
                    uImag = uReal * wImag + uImag * wReal
                    uReal = nuevoUReal
                    kk++
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