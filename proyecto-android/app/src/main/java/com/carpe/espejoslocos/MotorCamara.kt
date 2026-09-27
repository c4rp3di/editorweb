package com.carpe.espejoslocos

import android.graphics.Bitmap
import android.util.Size
import android.widget.ImageView
import androidx.camera.core.CameraSelector
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

    private var executor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null

    fun iniciar(activity: MainActivity, imageView: ImageView) {
        LogEspejos.i("MotorCamara.iniciar()")
        val futuro = ProcessCameraProvider.getInstance(activity)
        futuro.addListener({
            try {
                val provider = futuro.get()
                provider.unbindAll()

                imageAnalysis?.clearAnalyzer()
                executor?.shutdown()

                val selectorResolucion = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val analisis = ImageAnalysis.Builder()
                    .setResolutionSelector(selectorResolucion)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                val exec = Executors.newSingleThreadExecutor()
                executor = exec
                imageAnalysis = analisis

                analisis.setAnalyzer(exec, ProcesadorFrame(activity, imageView))

                val selector = if (usandoFrontal) CameraSelector.DEFAULT_FRONT_CAMERA
                               else CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(activity, selector, analisis)
                LogEspejos.i("bindToLifecycle OK (${if (usandoFrontal) "FRONTAL" else "TRASERA"})")
            } catch (e: Exception) {
                LogEspejos.e("Error crítico en iniciar()", e)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    fun alternarCamara(activity: MainActivity, imageView: ImageView) {
        usandoFrontal = !usandoFrontal
        iniciar(activity, imageView)
    }

    fun detener() {
        imageAnalysis?.clearAnalyzer()
        executor?.shutdown()
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

            // La clave ahora incluye los params: si cambia cualquier slider,
            // los mapas se recalculan.
            val claveParams = params.entries.joinToString(",") { "${it.key}=${it.value}" }
            val clave = "${filtro.id}|${matRotada.cols()}x${matRotada.rows()}|$intensidad|$claveParams"
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

            Imgproc.remap(matRotada, matDst, parMapas.first, parMapas.second, Imgproc.INTER_LINEAR)

            if (MotorCamara.usandoFrontal) {
                Core.flip(matDst, matDst, 1)
            }

            filtro.postProcesar?.invoke(matDst, params)

            val salida = Bitmap.createBitmap(matDst.cols(), matDst.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(matDst, salida)

            if (filtro.animado) {
                parMapas.first.release()
                parMapas.second.release()
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