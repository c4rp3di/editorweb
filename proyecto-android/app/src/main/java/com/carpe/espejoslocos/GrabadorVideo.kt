package com.carpe.espejoslocos

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Graba los Bitmap que ya han pasado por OpenCV.
 *
 * Flujo:
 * CameraX -> ImageAnalysis -> OpenCV -> Bitmap -> GrabadorVideo -> MP4
 *
 * Por tanto, el MP4 contiene el efecto aplicado y no la imagen original
 * de la cámara.
 *
 * La entrada del encoder es una Surface; cada Bitmap se pinta sobre ella.
 * No se utiliza CameraX VideoCapture.
 *
 * El vídeo se graba sin audio. Esto es intencionado: añadir el micrófono
 * requeriría multiplexar una segunda pista de audio manteniendo sincronía
 * con los frames procesados.
 */
class GrabadorVideo(
    private val activity: MainActivity,
    private val ancho: Int,
    private val alto: Int,
    private val fps: Int,
    private val onFinalizado: (ok: Boolean, mensaje: String) -> Unit
) {

    private val cola = LinkedBlockingQueue<Bitmap>(2)

    @Volatile
    private var detener = false

    @Volatile
    private var iniciado = false

    private var hilo: Thread? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerIniciado = false
    private var archivoTemporal: File? = null

    fun start() {
        if (iniciado) return

        val bitrate =
            (ancho * alto * fps * 0.10f)
                .toInt()
                .coerceIn(2_000_000, 16_000_000)

        val format =
            MediaFormat.createVideoFormat(
                MIME_TYPE,
                ancho,
                alto
            ).apply {

                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities
                        .COLOR_FormatSurface
                )

                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    bitrate
                )

                setInteger(
                    MediaFormat.KEY_FRAME_RATE,
                    fps
                )

                setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    1
                )
            }

        val c =
            MediaCodec.createEncoderByType(
                MIME_TYPE
            )

        c.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        codec = c
        surface = c.createInputSurface()

        val dir =
            File(
                activity.cacheDir,
                "grabaciones_espejos"
            )

        if (!dir.exists() && !dir.mkdirs()) {
            surface?.release()
            c.release()
            codec = null
            surface = null
            throw IllegalStateException(
                "No se pudo crear el directorio temporal"
            )
        }

        archivoTemporal =
            File(
                dir,
                "video_${System.currentTimeMillis()}.mp4"
            )

        muxer =
            MediaMuxer(
                archivoTemporal!!.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

        c.start()
        iniciado = true

        hilo =
            Thread(
                { codificar() },
                "EspejosLocos-VideoEncoder"
            ).also {
                it.start()
            }
    }

    /**
     * Recibe el Bitmap procesado.
     *
     * Se hace una copia porque ProcesadorFrame puede reciclar/reemplazar
     * el Bitmap inmediatamente después de actualizar el ImageView.
     */
    fun offerFrame(bitmap: Bitmap) {
        if (!iniciado || detener) return

        val copia =
            try {
                bitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )
            } catch (_: Exception) {
                return
            }

        if (!cola.offer(copia)) {
            // Mantener baja latencia: descartamos el frame más antiguo.
            val viejo = cola.poll()
            viejo?.recycle()

            if (!cola.offer(copia)) {
                copia.recycle()
            }
        }
    }

    fun stop() {
        if (!iniciado) return

        detener = true

        hilo?.let {
            try {
                it.join(5000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                LogEspejos.e(
                    "Interrupción esperando al grabador",
                    e
                )
            }
        }

        hilo = null
    }

    private fun codificar() {

        var error: Exception? = null

        try {
            val c =
                codec
                    ?: throw IllegalStateException(
                        "Codec no inicializado"
                    )

            val s =
                surface
                    ?: throw IllegalStateException(
                        "Surface no inicializada"
                    )

            val paint =
                Paint(
                    Paint.ANTI_ALIAS_FLAG or
                        Paint.FILTER_BITMAP_FLAG
                )

            while (!detener || cola.isNotEmpty()) {

                val bitmap =
                    cola.poll(
                        100,
                        TimeUnit.MILLISECONDS
                    ) ?: continue

                try {
                    val canvas =
                        s.lockCanvas(null)

                    try {
                        canvas.drawColor(
                            android.graphics.Color.BLACK
                        )

                        canvas.drawBitmap(
                            bitmap,
                            null,
                            android.graphics.Rect(
                                0,
                                0,
                                ancho,
                                alto
                            ),
                            paint
                        )
                    } finally {
                        s.unlockCanvasAndPost(
                            canvas
                        )
                    }

                    drainEncoder(
                        c,
                        endOfStream = false
                    )

                } finally {
                    bitmap.recycle()
                }
            }

            c.signalEndOfInputStream()

            drainEncoder(
                c,
                endOfStream = true
            )

        } catch (e: Exception) {
            error = e
            LogEspejos.e(
                "Error codificando vídeo procesado",
                e
            )
        } finally {

            try {
                muxer?.stop()
            } catch (_: Exception) {
            }

            try {
                muxer?.release()
            } catch (_: Exception) {
            }

            muxer = null

            try {
                codec?.stop()
            } catch (_: Exception) {
            }

            try {
                codec?.release()
            } catch (_: Exception) {
            }

            codec = null

            try {
                surface?.release()
            } catch (_: Exception) {
            }

            surface = null

            while (true) {
                val b = cola.poll() ?: break
                b.recycle()
            }

            iniciado = false

            val temporal = archivoTemporal
            archivoTemporal = null

            if (error == null && temporal != null && temporal.exists()) {
                try {
                    val uri =
                        guardarEnGaleria(temporal)

                    temporal.delete()

                    activity.runOnUiThread {
                        onFinalizado(
                            true,
                            "Vídeo guardado en Movies/EspejosLocos"
                        )
                    }

                    LogEspejos.i(
                        "Vídeo procesado guardado: $uri"
                    )

                } catch (e: Exception) {

                    temporal.delete()

                    LogEspejos.e(
                        "No se pudo copiar el vídeo a la galería",
                        e
                    )

                    activity.runOnUiThread {
                        onFinalizado(
                            false,
                            "Error guardando el vídeo"
                        )
                    }
                }

            } else {

                temporal?.delete()

                activity.runOnUiThread {
                    onFinalizado(
                        false,
                        "Error codificando el vídeo"
                    )
                }
            }
        }
    }

    private fun drainEncoder(
        c: MediaCodec,
        endOfStream: Boolean
    ) {

        val info =
            MediaCodec.BufferInfo()

        while (true) {

            val index =
                c.dequeueOutputBuffer(
                    info,
                    if (endOfStream) 10_000L else 0L
                )

            when {

                index ==
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {

                    if (!endOfStream) {
                        return
                    }
                }

                index ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    if (muxerIniciado) {
                        throw IllegalStateException(
                            "El formato del encoder cambió dos veces"
                        )
                    }

                    val formato =
                        c.outputFormat

                    trackIndex =
                        muxer!!.addTrack(
                            formato
                        )

                    muxer!!.start()
                    muxerIniciado = true
                }

                index >= 0 -> {

                    val buffer =
                        c.getOutputBuffer(index)
                            ?: throw IllegalStateException(
                                "OutputBuffer nulo"
                            )

                    if (
                        info.flags and
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        != 0
                    ) {
                        info.size = 0
                    }

                    if (
                        info.size > 0 &&
                        muxerIniciado
                    ) {

                        buffer.position(
                            info.offset
                        )

                        buffer.limit(
                            info.offset + info.size
                        )

                        muxer!!.writeSampleData(
                            trackIndex,
                            buffer,
                            info
                        )
                    }

                    c.releaseOutputBuffer(
                        index,
                        false
                    )

                    if (
                        info.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        != 0
                    ) {
                        return
                    }
                }
            }
        }
    }

    private fun guardarEnGaleria(
        temporal: File
    ): Uri {

        val nombre =
            "EspejosLocos_${System.currentTimeMillis()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val values =
                ContentValues().apply {
                    put(
                        MediaStore.Video.Media.DISPLAY_NAME,
                        nombre
                    )
                    put(
                        MediaStore.Video.Media.MIME_TYPE,
                        "video/mp4"
                    )
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "Movies/EspejosLocos"
                    )
                    put(
                        MediaStore.Video.Media.IS_PENDING,
                        1
                    )
                }

            val uri =
                activity.contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IllegalStateException(
                    "No se pudo crear el elemento de vídeo"
                )

            try {

                activity.contentResolver
                    .openOutputStream(uri)
                    ?.use { salida ->

                        FileInputStream(
                            temporal
                        ).use { entrada ->

                            entrada.copyTo(
                                salida,
                                64 * 1024
                            )
                        }
                    }
                    ?: throw IllegalStateException(
                        "No se pudo abrir la salida del vídeo"
                    )

                activity.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(
                            MediaStore.Video.Media.IS_PENDING,
                            0
                        )
                    },
                    null,
                    null
                )

                return uri

            } catch (e: Exception) {

                activity.contentResolver.delete(
                    uri,
                    null,
                    null
                )

                throw e
            }

        } else {

            @Suppress("DEPRECATION")
            val movies =
                Environment
                    .getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES
                    )

            val carpeta =
                File(
                    movies,
                    "EspejosLocos"
                )

            if (!carpeta.exists() &&
                !carpeta.mkdirs()
            ) {
                throw IllegalStateException(
                    "No se pudo crear Movies/EspejosLocos"
                )
            }

            val destino =
                File(
                    carpeta,
                    nombre
                )

            FileInputStream(
                temporal
            ).use { entrada ->

                FileOutputStream(
                    destino
                ).use { salida ->

                    entrada.copyTo(
                        salida,
                        64 * 1024
                    )
                }
            }

            android.media.MediaScannerConnection
                .scanFile(
                    activity,
                    arrayOf(
                        destino.absolutePath
                    ),
                    arrayOf("video/mp4"),
                    null
                )

            return Uri.fromFile(
                destino
            )
        }
    }

    companion object {
        private const val MIME_TYPE =
            "video/avc"
    }
}
