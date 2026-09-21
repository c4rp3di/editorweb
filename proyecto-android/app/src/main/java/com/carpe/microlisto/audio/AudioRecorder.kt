package com.carpe.microlisto.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File

/**
 * Captura audio del micrófono a 16 kHz mono PCM float y hace dos cosas con cada
 * bloque leído: escribirlo a un WAV y pasárselo al callback para que el pipeline
 * lo procese (VAD, transcripción).
 *
 * El buffer real se redondea a un múltiplo de 512 muestras para que Silero VAD
 * reciba siempre frames completos.
 */
class AudioRecorder(
    private val archivoWav: File,
    private val sampleRate: Int = 16000,
    private val tamanoFrame: Int = 512,
    private val onFrame: (FloatArray, Int) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var hilo: Thread? = null
    private var grabando = false
    private var wavWriter: WavWriter? = null

    @SuppressLint("MissingPermission")
    fun iniciar() {
        if (grabando) return

        val bufferMinimo = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (bufferMinimo <= 0) {
            throw IllegalStateException("AudioRecord no soporta PCM float a $sampleRate Hz")
        }
        // Redondear a múltiplo de tamanoFrame y con margen x3 para no perder muestras
        val framesNecesarios = (bufferMinimo + tamanoFrame - 1) / tamanoFrame
        val tamanoBuffer = framesNecesarios * tamanoFrame * 3

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            tamanoBuffer
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("No se pudo inicializar AudioRecord")
        }

        val writer = WavWriter(archivoWav, sampleRate)
        writer.abrir()

        audioRecord = record
        wavWriter = writer
        grabando = true

        record.startRecording()

        hilo = Thread {
            val buffer = FloatArray(tamanoFrame)
            while (grabando) {
                val leidos = record.read(buffer, 0, tamanoFrame, AudioRecord.READ_BLOCKING)
                if (leidos > 0) {
                    writer.escribirFloat(buffer, leidos)
                    onFrame(buffer, leidos)
                } else if (leidos < 0) {
                    // Error de lectura: salir del bucle
                    break
                }
            }
        }.also { it.priority = Thread.MAX_PRIORITY; it.start() }
    }

    fun parar() {
        if (!grabando) return
        grabando = false
        try {
            hilo?.join(1000)
        } catch (_: InterruptedException) {}
        hilo = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        try {
            wavWriter?.cerrar()
        } catch (_: Exception) {}
        wavWriter = null
    }

    fun estaGrabando(): Boolean = grabando
}