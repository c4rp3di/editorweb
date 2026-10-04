package com.carpe.espejoslocos

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaRecorder
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
 * El vídeo que entra en MediaRecorder es una Surface alimentada con el
 * Bitmap procesado, por lo que no se graba la imagen original de la cámara.
 * Opcionalmente MediaRecorder añade el micrófono como pista AAC en el mismo
 * MP4, manteniendo las pistas de vídeo y audio sincronizadas.
 */
class GrabadorVideo(
    private val activity: MainActivity,
    private val ancho: Int,
    private val alto: Int,
    private val fps: Int,
    private val conAudio: Boolean,
    private val onFinalizado: (ok: Boolean, mensaje: String) -> Unit
) {

    private val cola = LinkedBlockingQueue<Bitmap>(2)

    @Volatile
    private var detener = false

    @Volatile
    private var iniciado = false

    private var hilo: Thread? = null
    private var recorder: MediaRecorder? = null
    private var surface: Surface? = null
    private var archivoTemporal: File? = null

    fun start() {
        if (iniciado) return

        val bitrate =
            (ancho * alto * fps * 0.10f)
                .toInt()
                .coerceIn(2_000_000, 16_000_000)

        val dir =
            File(
                activity.cacheDir,
                "grabaciones_espejos"
            )

        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException(
                "No se pudo crear el directorio temporal"
            )
        }

        val temporal = File(
            dir,
            "video_${System.currentTimeMillis()}.mp4"
        )

        val r = MediaRecorder()

        try {
            // MediaRecorder exige que las fuentes se configuren antes del formato.
            if (conAudio) {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(temporal.absolutePath)

            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(ancho, alto)
            r.setVideoFrameRate(fps)
            r.setVideoEncodingBitRate(bitrate)

            if (conAudio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(44_100)
                r.setAudioChannels(1)
            }

            r.prepare()

            val s = r.surface
                ?: throw IllegalStateException(
                    "MediaRecorder no proporcionó una Surface"
                )

            recorder = r
            surface = s
            archivoTemporal = temporal

            r.start()
            iniciado = true
            detener = false

            hilo = Thread(
                { codificar() },
                "EspejosLocos-VideoRecorder"
            ).also { it.start() }

            LogEspejos.i(
                "MediaRecorder iniciado: ${ancho}x${alto}@${fps} audio=$conAudio"
            )
        } catch (e: Exception) {
            try {
                r.reset()
            } catch (_: Exception) {
            }
            try {
                r.release()
            } catch (_: Exception) {
            }
            temporal.delete()
            throw e
        }
    }

    /**
     * Recibe el Bitmap procesado.
     * Se copia porque ProcesadorFrame puede reutilizar/reemplazar el Bitmap
     * inmediatamente después de actualizar el ImageView.
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
            val r = recorder
                ?: throw IllegalStateException(
                    "MediaRecorder no inicializado"
                )

            val s = surface
                ?: throw IllegalStateException(
                    "Surface no inicializada"
                )

            val paint = Paint(
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
                    val canvas = s.lockCanvas(null)
                    try {
                        canvas.drawColor(Color.BLACK)
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
                        s.unlockCanvasAndPost(canvas)
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            // MediaRecorder genera el MP4 final al detenerse.
            try {
                r.stop()
            } catch (e: RuntimeException) {
                throw IllegalStateException(
                    "MediaRecorder no recibió suficientes frames de vídeo",
                    e
                )
            }

        } catch (e: Exception) {
            error = e
            LogEspejos.e(
                "Error grabando vídeo procesado",
                e
            )
        } finally {
            val temporal = archivoTemporal
            archivoTemporal = null

            try {
                recorder?.reset()
            } catch (_: Exception) {
            }
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            recorder = null

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

            if (error == null && temporal != null && temporal.exists()) {
                try {
                    val uri = guardarEnGaleria(temporal)
                    temporal.delete()

                    val mensaje = if (conAudio) {
                        "Vídeo con audio guardado en Movies/EspejosLocos"
                    } else {
                        "Vídeo guardado en Movies/EspejosLocos"
                    }

                    activity.runOnUiThread {
                        onFinalizado(true, mensaje)
                    }

                    LogEspejos.i(
                        "Vídeo procesado guardado: $uri audio=$conAudio"
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

    private fun guardarEnGaleria(temporal: File): Uri {
        val nombre =
            "EspejosLocos_${System.currentTimeMillis()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
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
                        FileInputStream(temporal).use { entrada ->
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
        }

        @Suppress("DEPRECATION")
        val movies =
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES
            )

        val carpeta = File(
            movies,
            "EspejosLocos"
        )

        if (!carpeta.exists() && !carpeta.mkdirs()) {
            throw IllegalStateException(
                "No se pudo crear Movies/EspejosLocos"
            )
        }

        val destino = File(carpeta, nombre)

        FileInputStream(temporal).use { entrada ->
            FileOutputStream(destino).use { salida ->
                entrada.copyTo(
                    salida,
                    64 * 1024
                )
            }
        }

        android.media.MediaScannerConnection.scanFile(
            activity,
            arrayOf(destino.absolutePath),
            arrayOf("video/mp4"),
            null
        )

        return Uri.fromFile(destino)
    }
}
