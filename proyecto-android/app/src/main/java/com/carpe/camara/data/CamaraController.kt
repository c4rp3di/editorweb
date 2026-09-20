package com.carpe.camara.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalCamera2Interop::class)
class CamaraController(private val contexto: Context) {

    private var proveedor: ProcessCameraProvider? = null
    private var camara: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val ejecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var estado: CamaraEstado = CamaraEstado()

    var alListo: ((Camera) -> Unit)? = null

    var idPrincipal: String? = null
    var idTele: String? = null
    var idUltra: String? = null

    var seleccionLenteRealDisponible: Boolean = false
        private set

    fun camaraActual(): Camera? = camara

    fun iniciar(
        cicloDeVida: LifecycleOwner,
        vistaPrevia: PreviewView,
        onListo: (Camera) -> Unit
    ) {
        alListo = onListo
        val futuro = ProcessCameraProvider.getInstance(contexto)
        futuro.addListener({
            try {
                proveedor = futuro.get()
                descubrirLentes()
                enlazar(cicloDeVida, vistaPrevia)
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando cámara", e)
            }
        }, ContextCompat.getMainExecutor(contexto))
    }

    /**
     * Enumera cámaras traseras. Si el fabricante expone las 3 físicas
     * separadas (Xiaomi suele hacerlo), las identificamos y permitimos
     * selección real. Si no, marcamos la bandera como false y usamos zoom.
     */
    private fun descubrirLentes() {
        val p = proveedor ?: return
        val traseras = p.availableCameraInfos.filter {
            it.lensFacing == CameraSelector.LENS_FACING_BACK
        }
        Log.d(TAG, "Cámaras traseras: ${traseras.size}")

        val conFocal = traseras.mapNotNull { info ->
            try {
                val c2 = Camera2CameraInfo.from(info)
                val focal = c2.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )?.firstOrNull()
                if (focal != null) Triple(info, focal, c2.cameraId) else null
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.second }

        when {
            conFocal.size >= 3 -> {
                idUltra = conFocal.first().third
                idPrincipal = conFocal[conFocal.size / 2].third
                idTele = conFocal.last().third
                seleccionLenteRealDisponible = true
            }
            conFocal.size == 2 -> {
                idPrincipal = conFocal[0].third
                idTele = conFocal[1].third
                seleccionLenteRealDisponible = true
            }
            conFocal.isNotEmpty() -> {
                idPrincipal = conFocal.first().third
                seleccionLenteRealDisponible = false
            }
        }
        Log.d(TAG, "P=$idPrincipal T=$idTele U=$idUltra · real=$seleccionLenteRealDisponible")
    }

    private fun selectorParaLente(lente: LenteFisica): CameraSelector {
        val idFisico = when (lente) {
            LenteFisica.PRINCIPAL -> idPrincipal
            LenteFisica.TELEOBJETIVO -> idTele
            LenteFisica.ULTRA_GRAN_ANGULAR -> idUltra
        }
        if (idFisico == null || !seleccionLenteRealDisponible) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                val coincide = infos.filter { info ->
                    try {
                        Camera2CameraInfo.from(info).cameraId == idFisico
                    } catch (e: Exception) {
                        false
                    }
                }
                coincide.ifEmpty { infos }
            }
            .build()
    }

    fun cambiarLente(nueva: LenteFisica, cicloDeVida: LifecycleOwner, vistaPrevia: PreviewView) {
        estado = estado.copy(lente = nueva)
        enlazar(cicloDeVida, vistaPrevia)
    }

    fun aplicarEstado(nuevo: CamaraEstado, cicloDeVida: LifecycleOwner, vistaPrevia: PreviewView) {
        estado = nuevo
        enlazar(cicloDeVida, vistaPrevia)
    }

    @SuppressLint("RestrictedApi")
    private fun enlazar(cicloDeVida: LifecycleOwner, vistaPrevia: PreviewView) {
        val p = proveedor ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(vistaPrevia.surfaceProvider)
        }

        val builderCaptura = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(
                if (estado.flashAuto) ImageCapture.FLASH_MODE_AUTO
                else ImageCapture.FLASH_MODE_OFF
            )

        val extender = Camera2Interop.Extender(builderCaptura)

        if (estado.modo == ModoCaptura.PRO) {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            if (estado.focoManual) {
                extender.setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    estado.distanciaFocoDioptras
                )
            }
            if (estado.isoManual || estado.exposicionManual) {
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF
                )
                if (estado.isoManual) {
                    extender.setCaptureRequestOption(
                        CaptureRequest.SENSOR_SENSITIVITY,
                        estado.iso
                    )
                }
                if (estado.exposicionManual) {
                    extender.setCaptureRequestOption(
                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                        estado.exposicionNs
                    )
                }
            }
            if (estado.wbManual) {
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_OFF
                )
                val (r, g, b) = kelvinAGanancias(estado.temperaturaK)
                extender.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    RggbChannelVector(r, g, g, b)
                )
            }
        }

        val captura = builderCaptura.build()
        val selector = selectorParaLente(estado.lente)

        try {
            p.unbindAll()
            camara = p.bindToLifecycle(cicloDeVida, selector, preview, captura)
            imageCapture = captura

            // Solo aplicamos zoom digital si NO tenemos selección real de lente.
            if (!seleccionLenteRealDisponible) {
                camara?.cameraControl?.let { control ->
                    val zoom = when (estado.lente) {
                        LenteFisica.PRINCIPAL -> 1f
                        LenteFisica.TELEOBJETIVO -> 2f
                        LenteFisica.ULTRA_GRAN_ANGULAR -> 0.6f
                    }
                    val zs = camara?.cameraInfo?.zoomState?.value
                    if (zs != null) {
                        control.setZoomRatio(zoom.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
                    }
                }
            } else {
                camara?.cameraControl?.setZoomRatio(1f)
            }

            alListo?.invoke(camara!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error enlazando cámara", e)
        }
    }

    private fun kelvinAGanancias(k: Int): Triple<Float, Float, Float> {
        val t = (k.coerceIn(2000, 8000)) / 100f
        val r: Float
        val g: Float
        val b: Float
        if (t <= 66f) {
            r = 1f
            g = ((99.4708025861 * Math.log(t.toDouble()) - 161.1195681661) / 255.0)
                .toFloat().coerceIn(0f, 1f)
        } else {
            r = ((329.698727446 * Math.pow(t - 60.0, -0.1332047592)) / 255.0)
                .toFloat().coerceIn(0f, 1f)
            g = ((288.1221695283 * Math.pow(t - 60.0, -0.0755148492)) / 255.0)
                .toFloat().coerceIn(0f, 1f)
        }
        b = when {
            t >= 66f -> 1f
            t <= 19f -> 0f
            else -> ((138.5177312231 * Math.log(t - 10.0) - 305.0447927307) / 255.0)
                .toFloat().coerceIn(0f, 1f)
        }
        return Triple(r.coerceAtLeast(0.1f), g.coerceAtLeast(0.1f), b.coerceAtLeast(0.1f))
    }

    fun capturar(onGuardado: (Boolean, String, Uri?) -> Unit) {
        val captura = imageCapture ?: run {
            onGuardado(false, "Cámara no lista", null)
            return
        }
        val nombre = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MACRO_$nombre.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CamaraMacro")
            }
        }
        val opciones = ImageCapture.OutputFileOptions.Builder(
            contexto.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            valores
        ).build()
        captura.takePicture(opciones, ejecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(resultado: ImageCapture.OutputFileResults) {
                onGuardado(true, "Foto guardada", resultado.savedUri)
            }
            override fun onError(excepcion: ImageCaptureException) {
                onGuardado(false, "Error: ${excepcion.message}", null)
            }
        })
    }

    fun liberar() {
        proveedor?.unbindAll()
        camara = null
        imageCapture = null
    }

    fun cerrar() {
        liberar()
        ejecutor.shutdown()
    }

    companion object {
        private const val TAG = "CamaraController"
    }
}