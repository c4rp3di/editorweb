package com.carpe.espejoslocos

import android.graphics.Bitmap
import android.util.Size
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pipeline: cámara → ImageAnalysis (RGBA) → Mat → filtro → Bitmap → ImageView.
 *
 * Usamos ImageAnalysis en vez de Preview porque necesitamos cada frame como
 * píxeles para deformarlo con OpenCV. El PreviewView que genera el editor
 * queda oculto y nuestro ImageView ocupa la pantalla completa.
 */
object MotorCamara {

    var filtroActual: Filtros.Filtro = Filtros.lista[0]
    var intensidad: Float = 0.85f
    var usandoFrontal: Boolean = false
    var tiempoInicio: Long = System.currentTimeMillis()

    private var executor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null

    fun iniciar(activity: MainActivity, imageView: ImageView) {
        val futuro = ProcessCameraProvider.getInstance(activity)
        futuro.addListener({
            val provider = futuro.get()
            provider.unbindAll() // tomamos el control: que el código generado no nos pise

            val analisis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            val exec = Executors.newSingleThreadExecutor()
            executor = exec
            imageAnalysis = analisis

            analisis.setAnalyzer(exec, ProcesadorFrame(activity, imageView))

            val selector = if (usandoFrontal) CameraSelector.DEFAULT_FRONT_CAMERA
                           else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.bindToLifecycle(activity, selector, analisis)
            } catch (e: Exception) {
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, analisis)
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
    private val matDst = Mat()
    private var mapasCache: Pair<Mat, Mat>? = null
    private var claveCache: String = ""

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val filtro = MotorCamara.filtroActual
            val intensidad = MotorCamara.intensidad
            val tiempo = (System.currentTimeMillis() - MotorCamara.tiempoInicio) / 1000f

            val bitmap = imagenABitmap(imageProxy)
            Utils.bitmapToMat(bitmap, matSrc)
            bitmap.recycle()

            val clave = "${filtro.id}|${matSrc.cols()}x${matSrc.rows()}|${intensidad}"
            val recalcular = filtro.animado || clave != claveCache

            val parMapas: Pair<Mat, Mat> = if (recalcular) {
                val nuevos = Filtros.crearMapas(filtro, matSrc.cols(), matSrc.rows(), intensidad, tiempo)
                if (!filtro.animado) {
                    mapasCache?.let { (a, b) -> a.release(); b.release() }
                    mapasCache = nuevos
                    claveCache = clave
                }
                nuevos
            } else {
                mapasCache!!
            }

            Imgproc.remap(matSrc, matDst, parMapas.first, parMapas.second, Imgproc.INTER_LINEAR)

            // Espejamos la cámara frontal para que se vea como un espejo real
            if (MotorCamara.usandoFrontal) {
                Core.flip(matDst, matDst, 1)
            }

            filtro.postProcesar?.invoke(matDst)

            val salida = Bitmap.createBitmap(matDst.cols(), matDst.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(matDst, salida)

            if (filtro.animado) {
                parMapas.first.release()
                parMapas.second.release()
            }

            activity.runOnUiThread {
                imageView.setImageBitmap(salida)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
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