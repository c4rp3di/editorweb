package com.carpe.espejoslocos

import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.widget.ImageView
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object MotorCamara {

    var filtroActual: Filtros.Filtro = Filtros.lista[0]
    var intensidad: Float = 0.85f
    var usandoFrontal: Boolean = false
    var tiempoInicio: Long = System.currentTimeMillis()

    @Volatile
    var fpsActual: Float = 0f

    private var executor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Grabación CameraX.
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null

    @Volatile
    private var grabando = false

    private var onCambioGrabacion: ((Boolean) -> Unit)? = null

    fun estaGrabando(): Boolean = grabando

    fun iniciar(
        activity: MainActivity,
        imageView: ImageView
    ) {
        LogEspejos.i("MotorCamara.iniciar()")

        // Un cambio de resolución/FPS/cámara no debe dejar
        // una grabación colgada.
        if (grabando) {
            LogEspejos.i(
                "Deteniendo grabación antes de reiniciar la cámara"
            )
            detenerGrabacion()
        }

        val futuro = ProcessCameraProvider.getInstance(activity)

        futuro.addListener({

            try {

                val providerLocal = futuro.get()

                provider = providerLocal

                providerLocal.unbindAll()

                imageAnalysis?.clearAnalyzer()
                executor?.shutdown()

                val res = ConfigCamara.resolucion

                LogEspejos.i(
                    "Resolución objetivo: " +
                        "${res.ancho}x${res.alto} (${res.etiqueta})"
                )

                val selectorResolucion =
                    androidx.camera.core.resolutionselector
                        .ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            androidx.camera.core.resolutionselector
                                .ResolutionStrategy(
                                    Size(
                                        res.ancho,
                                        res.alto
                                    ),
                                    androidx.camera.core.resolutionselector
                                        .ResolutionStrategy
                                        .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                        )
                        .build()

                val builder =
                    ImageAnalysis.Builder()
                        .setResolutionSelector(
                            selectorResolucion
                        )
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .setOutputImageFormat(
                            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )

                ConfigCamara.fps?.let { fpsObjetivo ->

                    try {

                        Camera2Interop.Extender(builder)
                            .setCaptureRequestOption(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                Range(
                                    fpsObjetivo,
                                    fpsObjetivo
                                )
                            )

                        LogEspejos.i(
                            "FPS objetivo: $fpsObjetivo"
                        )

                    } catch (e: Exception) {

                        LogEspejos.w(
                            "FPS $fpsObjetivo no soportado, " +
                                "se usa auto: ${e.message}"
                        )
                    }
                }

                val analisis = builder.build()

                val exec =
                    Executors.newSingleThreadExecutor()

                executor = exec
                imageAnalysis = analisis

                analisis.setAnalyzer(
                    exec,
                    ProcesadorFrame(
                        activity,
                        imageView
                    )
                )

                // VideoCapture comparte el mismo ciclo de vida
                // y selector que el análisis.
                //
                // IMPORTANTE:
                // el vídeo guardado es la señal original de cámara.
                // El ImageView continúa mostrando el procesamiento
                // OpenCV.
                val recorder =
                    Recorder.Builder().build()

                val video =
                    VideoCapture.withOutput(recorder)

                videoCapture = video

                val selector =
                    if (usandoFrontal) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                providerLocal.bindToLifecycle(
                    activity,
                    selector,
                    analisis,
                    video
                )

                LogEspejos.i(
                    "bindToLifecycle OK (" +
                        if (usandoFrontal) {
                            "FRONTAL"
                        } else {
                            "TRASERA"
                        } +
                        ") · vídeo disponible"
                )

            } catch (e: Exception) {

                LogEspejos.e(
                    "Error crítico en iniciar()",
                    e
                )
            }

        }, ContextCompat.getMainExecutor(activity))
    }

    /**
     * Reinicia la cámara cuando cambia la configuración.
     */
    fun reiniciar(
        activity: MainActivity,
        imageView: ImageView
    ) {
        LogEspejos.i(
            "MotorCamara.reiniciar() — cambio de configuración"
        )

        iniciar(
            activity,
            imageView
        )
    }

    /**
     * Cambia entre cámara frontal y trasera.
     */
    fun alternarCamara(
        activity: MainActivity,
        imageView: ImageView
    ) {
        usandoFrontal = !usandoFrontal

        iniciar(
            activity,
            imageView
        )
    }

    /**
     * Empieza a grabar en:
     *
     * Movies/EspejosLocos
     *
     * El audio es opcional.
     */
    fun iniciarGrabacion(
        activity: MainActivity,
        conAudio: Boolean,
        onCambioEstado: (Boolean) -> Unit
    ) {

        if (
            grabando ||
            currentRecording != null
        ) {
            return
        }

        val capturaVideo =
            videoCapture ?: run {

                LogEspejos.w(
                    "Grabación ignorada: " +
                        "VideoCapture todavía no está preparado"
                )

                android.widget.Toast.makeText(
                    activity,
                    "La cámara todavía está iniciando",
                    android.widget.Toast.LENGTH_SHORT
                ).show()

                return
            }

        val nombre =
            "EspejosLocos_${System.currentTimeMillis()}.mp4"

        val valores =
            ContentValues().apply {

                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    nombre
                )

                put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4"
                )

                if (
                    android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.Q
                ) {

                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "Movies/EspejosLocos"
                    )

                    put(
                        MediaStore.Video.Media.IS_PENDING,
                        1
                    )
                }
            }

        val opciones =
            MediaStoreOutputOptions
                .Builder(
                    activity.contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                )
                .setContentValues(valores)
                .build()

        try {

            onCambioGrabacion = onCambioEstado

            var pendiente =
                capturaVideo.output
                    .prepareRecording(
                        activity,
                        opciones
                    )

            if (
                conAudio &&
                ContextCompat.checkSelfPermission(
                    activity,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                pendiente =
                    pendiente.withAudioEnabled()
            }

            currentRecording =
                pendiente.start(
                    ContextCompat.getMainExecutor(activity)
                ) { evento ->

                    when (evento) {

                        is VideoRecordEvent.Start -> {

                            grabando = true

                            LogEspejos.i(
                                "Grabación iniciada · " +
                                    "audio=" +
                                    (
                                        conAudio &&
                                            ContextCompat
                                                .checkSelfPermission(
                                                    activity,
                                                    android.Manifest
                                                        .permission
                                                        .RECORD_AUDIO
                                                ) ==
                                            PackageManager.PERMISSION_GRANTED
                                    )
                            )

                            onCambioGrabacion?.invoke(true)
                        }

                        is VideoRecordEvent.Finalize -> {

                            grabando = false
                            currentRecording = null

                            if (!evento.hasError()) {

                                if (
                                    android.os.Build.VERSION.SDK_INT >=
                                    android.os.Build.VERSION_CODES.Q
                                ) {

                                    val uri =
                                        evento.outputResults
                                            .outputUri

                                    if (
                                        uri !=
                                        android.net.Uri.EMPTY
                                    ) {

                                        activity.contentResolver.update(
                                            uri,
                                            ContentValues().apply {
                                                put(
                                                    MediaStore.Video.Media
                                                        .IS_PENDING,
                                                    0
                                                )
                                            },
                                            null,
                                            null
                                        )
                                    }
                                }

                                LogEspejos.i(
                                    "Vídeo guardado: $nombre"
                                )

                                android.widget.Toast.makeText(
                                    activity,
                                    "Vídeo guardado en " +
                                        "Movies/EspejosLocos",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()

                            } else {

                                LogEspejos.e(
                                    "Error al finalizar grabación: " +
                                        "código=${evento.error}"
                                )

                                android.widget.Toast.makeText(
                                    activity,
                                    "Error al grabar vídeo: " +
                                        "${evento.error}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }

                            onCambioGrabacion?.invoke(false)
                            onCambioGrabacion = null
                        }
                    }
                }

        } catch (e: Exception) {

            currentRecording = null
            grabando = false
            onCambioGrabacion = null

            LogEspejos.e(
                "No se pudo iniciar la grabación",
                e
            )

            android.widget.Toast.makeText(
                activity,
                "No se pudo iniciar la grabación",
                android.widget.Toast.LENGTH_LONG
            ).show()

            onCambioEstado(false)
        }
    }

    /**
     * Detiene la grabación actual.
     */
    fun detenerGrabacion() {

        val grabacion = currentRecording

        if (grabacion != null) {

            LogEspejos.i(
                "Solicitando parada de grabación"
            )

            grabacion.stop()
        }
    }

    /**
     * Libera todos los recursos de la cámara.
     */
    fun detener() {

        detenerGrabacion()

        imageAnalysis?.clearAnalyzer()

        executor?.shutdown()

        executor = null
        imageAnalysis = null
        videoCapture = null

        provider?.unbindAll()
        provider = null

        grabando = false
        onCambioGrabacion = null
    }
}

private class ProcesadorFrame(
    private val activity: MainActivity,
    private val imageView: ImageView
) : ImageAnalysis.Analyzer {

    private val matSrc = Mat()
    private val matRotada = Mat()
    private val matDst = Mat()

    private var mapasCache: Pair<Mat, Mat>? = null
    private var claveCache: String = ""

    private var primerFrame = true
    private var erroresConsecutivos = 0
    private var ultimoError = ""

    private var framesContados = 0
    private var ultimoConteo =
        System.currentTimeMillis()

    override fun analyze(
        imageProxy: ImageProxy
    ) {

        try {

            val rotacion =
                imageProxy.imageInfo.rotationDegrees

            if (primerFrame) {

                primerFrame = false

                LogEspejos.i(
                    "Primer frame: " +
                        "${imageProxy.width}x${imageProxy.height} " +
                        "rotación=$rotacion°"
                )
            }

            val filtro =
                MotorCamara.filtroActual

            val intensidad =
                MotorCamara.intensidad

            val params =
                Filtros.mapParams(filtro)

            val tiempo =
                (
                    System.currentTimeMillis() -
                        MotorCamara.tiempoInicio
                    ) / 1000f

            val bitmap =
                imagenABitmap(imageProxy)

            Utils.bitmapToMat(
                bitmap,
                matSrc
            )

            bitmap.recycle()

            rotarMat(
                matSrc,
                matRotada,
                rotacion
            )

            val claveParams =
                params.entries.joinToString(",") {
                    "${it.key}=${it.value}"
                }

            val clave =
                "${filtro.id}|" +
                    "${matRotada.cols()}x${matRotada.rows()}|" +
                    "$intensidad|" +
                    claveParams

            val recalcular =
                filtro.animado ||
                    clave != claveCache

            val parMapas: Pair<Mat, Mat> =
                if (recalcular) {

                    val nuevos =
                        Filtros.crearMapas(
                            filtro,
                            matRotada.cols(),
                            matRotada.rows(),
                            intensidad,
                            tiempo,
                            params
                        )

                    if (!filtro.animado) {

                        mapasCache?.let { (a, b) ->
                            a.release()
                            b.release()
                        }

                        mapasCache = nuevos
                        claveCache = clave
                    }

                    nuevos

                } else {

                    mapasCache!!
                }

            Imgproc.remap(
                matRotada,
                matDst,
                parMapas.first,
                parMapas.second,
                ConfigCamara.interpolacion
            )

            if (
                MotorCamara.usandoFrontal &&
                ConfigCamara.espejarFrontal
            ) {

                Core.flip(
                    matDst,
                    matDst,
                    1
                )
            }

            filtro.postProcesar?.invoke(
                matDst,
                params
            )

            val salida =
                Bitmap.createBitmap(
                    matDst.cols(),
                    matDst.rows(),
                    Bitmap.Config.ARGB_8888
                )

            Utils.matToBitmap(
                matDst,
                salida
            )

            if (filtro.animado) {

                parMapas.first.release()
                parMapas.second.release()
            }

            // Contador de FPS.
            framesContados++

            val ahora =
                System.currentTimeMillis()

            val delta =
                ahora - ultimoConteo

            if (delta >= 500) {

                MotorCamara.fpsActual =
                    framesContados * 1000f / delta

                framesContados = 0
                ultimoConteo = ahora
            }

            erroresConsecutivos = 0

            activity.runOnUiThread {

                imageView.setImageBitmap(
                    salida
                )
            }

        } catch (e: Exception) {

            erroresConsecutivos++

            val msg =
                e.message
                    ?: e.javaClass.simpleName

            if (
                msg != ultimoError ||
                erroresConsecutivos == 1
            ) {

                LogEspejos.e(
                    "Error en analyze() " +
                        "(#$erroresConsecutivos)",
                    e
                )

                ultimoError = msg
            }

        } finally {

            imageProxy.close()
        }
    }

    private fun rotarMat(
        src: Mat,
        dst: Mat,
        grados: Int
    ) {

        when (grados) {

            90 ->
                Core.rotate(
                    src,
                    dst,
                    Core.ROTATE_90_CLOCKWISE
                )

            180 ->
                Core.rotate(
                    src,
                    dst,
                    Core.ROTATE_180
                )

            270 ->
                Core.rotate(
                    src,
                    dst,
                    Core.ROTATE_90_COUNTERCLOCKWISE
                )

            else ->
                src.copyTo(dst)
        }
    }

    private fun imagenABitmap(
        image: ImageProxy
    ): Bitmap {

        val plano =
            image.planes[0]

        val buffer =
            plano.buffer

        val pixelStride =
            plano.pixelStride

        val rowStride =
            plano.rowStride

        val rowPadding =
            rowStride -
                pixelStride * image.width

        if (
            rowPadding == 0 &&
            pixelStride == 4
        ) {

            val bmp =
                Bitmap.createBitmap(
                    image.width,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )

            bmp.copyPixelsFromBuffer(buffer)

            return bmp
        }

        val anchoRelleno =
            rowStride / pixelStride

        val bmpRelleno =
            Bitmap.createBitmap(
                anchoRelleno,
                image.height,
                Bitmap.Config.ARGB_8888
            )

        bmpRelleno.copyPixelsFromBuffer(
            buffer
        )

        val recortado =
            Bitmap.createBitmap(
                bmpRelleno,
                0,
                0,
                image.width,
                image.height
            )

        bmpRelleno.recycle()

        return recortado
    }
}