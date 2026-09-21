package com.carpe.microlisto.vad

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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

    fun calcular(audio: FloatArray): Array<FloatArray> {
        val numFrames = if (audio.size < frameLength) 0
                        else (audio.size - frameLength) / frameShift + 1
        if (numFrames <= 0) return emptyArray()

        val resultado: Array<FloatArray> = Array(numFrames) { FloatArray(numMelBins) }

        val audioPre = FloatArray(audio.size)
        audioPre[0] = audio[0]
        var iPre = 1
        while (iPre < audio.size) {
            audioPre[iPre] = audio[iPre] - preemph * audio[iPre - 1]
            iPre++
        }

        val frame = FloatArray(fftSize)
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)

        var f = 0
        while (f < numFrames) {
            val inicio = f * frameShift

            var suma = 0f
            var i = 0
            while (i < frameLength) {
                val v = audioPre[inicio + i]
                frame[i] = v
                suma += v
                i++
            }
            if (removeDc && frameLength > 0) {
                val media = suma / frameLength
                var j = 0
                while (j < frameLength) {
                    frame[j] = frame[j] - media
                    j++
                }
            }

            var j2 = 0
            while (j2 < frameLength) {
                frame[j2] = frame[j2] * ventana[j2]
                j2++
            }
            var j3 = frameLength
            while (j3 < fftSize) {
                frame[j3] = 0f
                j3++
            }

            var j4 = 0
            while (j4 < fftSize) {
                real[j4] = frame[j4]
                imag[j4] = 0f
                j4++
            }
            fft(real, imag)

            val potencia = FloatArray(numBins)
            var k = 0
            while (k < numBins) {
                potencia[k] = real[k] * real[k] + imag[k] * imag[k]
                k++
            }

            var m = 0
            while (m < numMelBins) {
                var sumaBand = 0f
                val banco = melBanks[m]
                var kk = 0
                while (kk < numBins) {
                    if (banco[kk] != 0f) sumaBand += banco[kk] * potencia[kk]
                    kk++
                }
                if (sumaBand < 1e-10f) sumaBand = 1e-10f
                resultado[f][m] = ln(sumaBand)
                m++
            }
            f++
        }

        aplicarCmn(resultado)
        return resultado
    }

    private fun aplicarCmn(matriz: Array<FloatArray>) {
        if (matriz.isEmpty()) return
        val numFrames = matriz.size
        val medias = FloatArray(numMelBins)

        var f = 0
        while (f < numFrames) {
            var m = 0
            while (m < numMelBins) {
                medias[m] = medias[m] + matriz[f][m]
                m++
            }
            f++
        }

        val divisor = numFrames.toFloat()
        var m = 0
        while (m < numMelBins) {
            medias[m] = medias[m] / divisor
            m++
        }

        f = 0
        while (f < numFrames) {
            m = 0
            while (m < numMelBins) {
                matriz[f][m] = matriz[f][m] - medias[m]
                m++
            }
            f++
        }
    }

    private fun ventanaPovey(n: Int): FloatArray {
        val w = FloatArray(n)
        var i = 0
        while (i < n) {
            val x = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            w[i] = x.pow(0.85).toFloat()
            i++
        }
        return w
    }

    private fun crearMelBanks(): Array<FloatArray> {
        val fMax = if (highFreq > 0f) highFreq else (sampleRate / 2).toFloat()
        val melLow = hzAMel(lowFreq)
        val melHigh = hzAMel(fMax)

        val puntos = FloatArray(numMelBins + 2)
        var i = 0
        while (i < numMelBins + 2) {
            val m = melLow + (melHigh - melLow) * i / (numMelBins + 1)
            puntos[i] = melAhz(m)
            i++
        }

        val frecuenciaBins = FloatArray(numBins)
        var k = 0
        while (k < numBins) {
            frecuenciaBins[k] = k.toFloat() * sampleRate / fftSize
            k++
        }

        val bancos: Array<FloatArray> = Array(numMelBins) { FloatArray(numBins) }
        var m = 1
        while (m <= numMelBins) {
            val fIzq = puntos[m - 1]
            val fCen = puntos[m]
            val fDer = puntos[m + 1]
            k = 0
            while (k < numBins) {
                val f = frecuenciaBins[k]
                var v = 0f
                if (f in fIzq..fCen && fCen > fIzq) {
                    v = (f - fIzq) / (fCen - fIzq)
                } else if (f in fCen..fDer && fDer > fCen) {
                    v = (fDer - f) / (fDer - fCen)
                }
                bancos[m - 1][k] = v
                k++
            }
            m++
        }
        return bancos
    }

    private fun hzAMel(hz: Float): Float = 1127f * ln(1f + hz / 700f)
    private fun melAhz(mel: Float): Float = 700f * (exp(mel / 1127f) - 1f)

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