package com.carpe.espejoslocos

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import android.widget.ImageView
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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

    @Volatile var fpsActual: Float = 0f

    @Volatile var toqueActivo: Boolean = false
        private set
    @Volatile var toqueX: Float = 0.5f
        private set
    @Volatile var toqueY: Float = 0.5f
        private set

    private var camara: Camera? = null
    @Volatile var zoomActual: Float = 1f
        private set

    /** Indica si se está grabando el vídeo procesado por OpenCV. */
    @Volatile var estaGrabando: Boolean = false
        private set

    private var grabadorVideo: GrabadorVideo? = null

    fun iniciarGrabacion(activity: MainActivity, conAudio: Boolean = ConfigCamara.grabarConAudio) {
        if (estaGrabando) return

        if (conAudio && ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ConfigCamara.grabarConAudio = false
            android.widget.Toast.makeText(
                activity,
                "No hay permiso de micrófono; se grabará sin audio",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val ancho = ConfigCamara.resolucion.ancho
        val alto = ConfigCamara.resolucion.alto
        val fps = (ConfigCamara.fps ?: 30).coerceIn(15, 60)

        try {
            val grabador = GrabadorVideo(
                activity = activity,
                ancho = ancho,
                alto = alto,
                fps = fps,
                conAudio = conAudio,
                onFinalizado = { ok, mensaje ->
                    activity.runOnUiThread {
                        estaGrabando = false
                        grabadorVideo = null
                        if (mensaje.isNotBlank()) {
                            android.widget.Toast.makeText(
                                activity,
                                mensaje,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )

            grabadorVideo = grabador
            grabador.start()
            estaGrabando = true
            LogEspejos.i("Grabación de vídeo procesado iniciada ${ancho}x${alto}@${fps} audio=$conAudio")

        } catch (e: Exception) {
            grabadorVideo = null
            estaGrabando = false
            LogEspejos.e("No se pudo iniciar la grabación procesada", e)
            android.widget.Toast.makeText(
                activity,
                "No se pudo iniciar la grabación",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    fun detenerGrabacion() {
        grabadorVideo?.stop()
    }

    fun enviarFrameAlGrabador(bitmap: Bitmap) {
        if (estaGrabando) {
            grabadorVideo?.offerFrame(bitmap)
        }
    }

    fun ajustarZoom(factor: Float) {
        val actual = zoomActual
        val nuevo = (actual * factor).coerceIn(1f, 8f)
        zoomActual = nuevo
        camara?.cameraControl?.setZoomRatio(nuevo)
    }

    fun establecerPuntoTactil(xVista: Float, yVista: Float, anchoVista: Int, altoVista: Int) {
        if (anchoVista <= 0 || altoVista <= 0) return
        toqueActivo = true

        val anchoImagen = ultimoAnchoFrame
        val altoImagen = ultimoAltoFrame
        if (anchoImagen <= 0 || altoImagen <= 0) {
            toqueX = (xVista / anchoVista).coerceIn(0f, 1f)
            toqueY = (yVista / altoVista).coerceIn(0f, 1f)
            return
        }

        // El ImageView usa CENTER_CROP: convertimos la posición del dedo
        // a coordenadas reales del frame procesado.
        val escala = maxOf(
            anchoVista.toFloat() / anchoImagen,
            altoVista.toFloat() / altoImagen
        )
        val mostradoAncho = anchoImagen * escala
        val mostradoAlto = altoImagen * escala
        val margenX = (anchoVista - mostradoAncho) / 2f
        val margenY = (altoVista - mostradoAlto) / 2f
        toqueX = ((xVista - margenX) / mostradoAncho).coerceIn(0f, 1f)
        toqueY = ((yVista - margenY) / mostradoAlto).coerceIn(0f, 1f)
    }

    fun limpiarPuntoTactil() {
        toqueActivo = false
    }

    private var executor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null
    @Volatile var ultimoAnchoFrame: Int = 0
    @Volatile var ultimoAltoFrame: Int = 0

    fun iniciar(activity: MainActivity, imageView: ImageView) {
        LogEspejos.i("MotorCamara.iniciar()")
        val futuro = ProcessCameraProvider.getInstance(activity)
        futuro.addListener({
            try {
                val provider = futuro.get()
                provider.unbindAll()

                imageAnalysis?.clearAnalyzer()
                executor?.shutdown()

                val res = ConfigCamara.resolucion
                LogEspejos.i("Resolución objetivo: ${res.ancho}x${res.alto} (${res.etiqueta})")

                val selectorResolucion = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(res.ancho, res.alto),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val builder = ImageAnalysis.Builder()
                    .setResolutionSelector(selectorResolucion)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

                ConfigCamara.fps?.let { fpsObjetivo ->
                    try {
                        Camera2Interop.Extender(builder).setCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(fpsObjetivo, fpsObjetivo)
                        )
                        LogEspejos.i("FPS objetivo: $fpsObjetivo")
                    } catch (e: Exception) {
                        LogEspejos.w("FPS $fpsObjetivo no soportado, se usa auto: ${e.message}")
                    }
                }

                val analisis = builder.build()

                val exec = Executors.newSingleThreadExecutor()
                executor = exec
                imageAnalysis = analisis

                analisis.setAnalyzer(exec, ProcesadorFrame(activity, imageView))

                val selector = if (usandoFrontal) CameraSelector.DEFAULT_FRONT_CAMERA
                               else CameraSelector.DEFAULT_BACK_CAMERA
                camara = provider.bindToLifecycle(activity, selector, analisis)
                camara?.cameraControl?.setZoomRatio(zoomActual)
                LogEspejos.i("bindToLifecycle OK (${if (usandoFrontal) "FRONTAL" else "TRASERA"})")
            } catch (e: Exception) {
                LogEspejos.e("Error crítico en iniciar()", e)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    /** Rebindea sin alternar cámara. La usa el panel de ajustes al cambiar resolución o FPS. */
    fun reiniciar(activity: MainActivity, imageView: ImageView) {
        LogEspejos.i("MotorCamara.reiniciar() — cambio de configuración")
        iniciar(activity, imageView)
    }

    fun alternarCamara(activity: MainActivity, imageView: ImageView) {
        usandoFrontal = !usandoFrontal
        iniciar(activity, imageView)
    }

    fun detener() {
        grabadorVideo?.stop()
        grabadorVideo = null
        estaGrabando = false
        imageAnalysis?.clearAnalyzer()
        executor?.shutdown()
        camara = null
        executor = null
        imageAnalysis = null
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
    private var ultimoConteo = System.currentTimeMillis()

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val rotacion = imageProxy.imageInfo.rotationDegrees
            if (primerFrame) {
                primerFrame = false
                LogEspejos.i("Primer frame: ${imageProxy.width}x${imageProxy.height} rotación=$rotacion°")
            }

            val filtro = MotorCamara.filtroActual
            val intensidad = MotorCamara.intensidad
            val params = Filtros.mapParams(filtro)
            val tiempo = (System.currentTimeMillis() - MotorCamara.tiempoInicio) / 1000f

            val bitmap = imagenABitmap(imageProxy)
            Utils.bitmapToMat(bitmap, matSrc)
            bitmap.recycle()

            rotarMat(matSrc, matRotada, rotacion)

            val claveParams = params.entries.joinToString(",") { "${it.key}=${it.value}" }
            MotorCamara.ultimoAnchoFrame = matRotada.cols()
            MotorCamara.ultimoAltoFrame = matRotada.rows()
            val clave = "${filtro.id}|${matRotada.cols()}x${matRotada.rows()}|$intensidad|$claveParams|touch=${MotorCamara.toqueActivo}:${MotorCamara.toqueX}:${MotorCamara.toqueY}"
            val recalcular = filtro.animado || clave != claveCache

            val parMapas: Pair<Mat, Mat> = if (recalcular) {
                val nuevos = Filtros.crearMapas(
                    filtro, matRotada.cols(), matRotada.rows(), intensidad, tiempo, params
                )
                if (!filtro.animado) {
                    mapasCache?.let { (a, b) -> a.release(); b.release() }
                    mapasCache = nuevos
                    claveCache = clave
                }
                nuevos
            } else {
                mapasCache!!
            }

            Imgproc.remap(
                matRotada, matDst,
                parMapas.first, parMapas.second,
                ConfigCamara.interpolacion
            )

            if (MotorCamara.usandoFrontal && ConfigCamara.espejarFrontal) {
                Core.flip(matDst, matDst, 1)
            }

            filtro.postProcesar?.invoke(matDst, params)

            val salida = Bitmap.createBitmap(matDst.cols(), matDst.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(matDst, salida)

            // Este bitmap es EXACTAMENTE el frame que se muestra en pantalla.
            // El grabador recibe una copia para codificarlo en el MP4.
            MotorCamara.enviarFrameAlGrabador(salida)

            if (filtro.animado) {
                parMapas.first.release()
                parMapas.second.release()
            }

            // Contador de FPS
            framesContados++
            val ahora = System.currentTimeMillis()
            val delta = ahora - ultimoConteo
            if (delta >= 500) {
                MotorCamara.fpsActual = framesContados * 1000f / delta
                framesContados = 0
                ultimoConteo = ahora
            }

            erroresConsecutivos = 0
            activity.runOnUiThread {
                imageView.setImageBitmap(salida)
            }
        } catch (e: Exception) {
            erroresConsecutivos++
            val msg = e.message ?: e.javaClass.simpleName
            if (msg != ultimoError || erroresConsecutivos == 1) {
                LogEspejos.e("Error en analyze() (#$erroresConsecutivos)", e)
                ultimoError = msg
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun rotarMat(src: Mat, dst: Mat, grados: Int) {
        when (grados) {
            90  -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> src.copyTo(dst)
        }
    }

    private fun imagenABitmap(image: ImageProxy): Bitmap {
        val plano = image.planes[0]
        val buffer = plano.buffer
        val pixelStride = plano.pixelStride
        val rowStride = plano.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        if (rowPadding == 0 && pixelStride == 4) {
            val bmp = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            return bmp
        }

        val anchoRelleno = rowStride / pixelStride
        val bmpRelleno = Bitmap.createBitmap(anchoRelleno, image.height, Bitmap.Config.ARGB_8888)
        bmpRelleno.copyPixelsFromBuffer(buffer)
        val recortado = Bitmap.createBitmap(bmpRelleno, 0, 0, image.width, image.height)
        bmpRelleno.recycle()
        return recortado
    }
}
