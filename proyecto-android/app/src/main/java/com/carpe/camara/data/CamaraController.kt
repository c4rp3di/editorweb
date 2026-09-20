package com.carpe.camara.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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

    private val prefs: SharedPreferences =
        contexto.getSharedPreferences("camara_prefs", Context.MODE_PRIVATE)

    var estado: CamaraEstado = CamaraEstado()
        private set

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
        cargarEstadoGuardado()
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

    private fun cargarEstadoGuardado() {
        try {
            estado = CamaraEstado(
                lente = LenteFisica.valueOf(
                    prefs.getString("lente", LenteFisica.PRINCIPAL.name)
                        ?: LenteFisica.PRINCIPAL.name),
                modo = ModoCaptura.valueOf(
                    prefs.getString("modo", ModoCaptura.AUTO.name)
                        ?: ModoCaptura.AUTO.name),
                iso = prefs.getInt("iso", 400),
                isoManual = prefs.getBoolean("isoManual", false),
                exposicionNs = prefs.getLong("exposicionNs", 16_666_666L),
                exposicionManual = prefs.getBoolean("exposicionManual", false),
                distanciaFocoDioptras = prefs.getFloat("foco", 0f),
                focoManual = prefs.getBoolean("focoManual", false),
                temperaturaK = prefs.getInt("wb", 5000),
                wbManual = prefs.getBoolean("wbManual", false),
                flashAuto = prefs.getBoolean("flashAuto", true),
                temporizador = Temporizador.valueOf(
                    prefs.getString("timer", Temporizador.OFF.name)
                        ?: Temporizador.OFF.name),
                mostrarGrid = prefs.getBoolean("grid", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando preferencias, usando valores por defecto", e)
            estado = CamaraEstado()
        }
    }

    private fun guardarEstado() {
        prefs.edit()
            .putString("lente", estado.lente.name)
            .putString("modo", estado.modo.name)
            .putInt("iso", estado.iso)
            .putBoolean("isoManual", estado.isoManual)
            .putLong("exposicionNs", estado.exposicionNs)
            .putBoolean("exposicionManual", estado.exposicionManual)
            .putFloat("foco", estado.distanciaFocoDioptras)
            .putBoolean("focoManual", estado.focoManual)
            .putInt("wb", estado.temperaturaK)
            .putBoolean("wbManual", estado.wbManual)
            .putBoolean("flashAuto", estado.flashAuto)
            .putString("timer", estado.temporizador.name)
            .putBoolean("grid", estado.mostrarGrid)
            .apply()
    }

    private fun descubrirLentes() {
        val p = proveedor ?: return
        val traseras = p.availableCameraInfos.filter {
            it.lensFacing == CameraSelector.LENS_FACING_BACK
        }
        Log.d(TAG, "Cámaras traseras listadas por CameraX: ${traseras.size}")

        if (traseras.size >= 2) {
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
            }
            Log.d(TAG, "MODO SEPARADO · P=$idPrincipal T=$idTele U=$idUltra")
            return
        }

        if (traseras.size == 1) {
            val info = traseras.first()
            idPrincipal = try { Camera2CameraInfo.from(info).cameraId } catch (e: Exception) { null }
            seleccionLenteRealDisponible = false
            Log.d(TAG, "MODO LÓGICO · id=$idPrincipal · zoom controlará lente")
        }
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
        guardarEstado()
        enlazar(cicloDeVida, vistaPrevia)
    }

    fun aplicarEstado(nuevo: CamaraEstado, cicloDeVida: LifecycleOwner, vistaPrevia: PreviewView) {
        estado = nuevo
        guardarEstado()
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

    // ============================================================
    // DIAGNÓSTICO — Consulta al HAL de la cámara activa
    // ============================================================
    // Devuelve un texto con lo que Camera2 reporta de la cámara que está
    // en uso ahora mismo. Con esto verificamos:
    //   · Cuántas aperturas (diafragmas) soporta el hardware
    //   · Cuál es el rango real de ISO
    //   · Cuál es el rango real de exposición (para saber si podemos hacer
    //     exposiciones largas tipo 1s/2s/4s o está capado)
    //   · La distancia mínima de enfoque (clave para macro)
    //   · Qué lentes físicas hay escondidas dentro de la cámara lógica
    //     y sus focales reales.
    fun obtenerDiagnostico(): String {
        val sb = StringBuilder()
        val cam = camara ?: return "Cámara aún no inicializada. Espera unos segundos."

        try {
            val c2Info = Camera2CameraInfo.from(cam.cameraInfo)
            val idCam = c2Info.cameraId
            sb.appendLine("🎥 Cámara activa: ID \"$idCam\"")
            sb.appendLine("🔭 Lente UI: ${estado.lente.etiqueta}")
            sb.appendLine("🔄 Zoom actual: %.2fx".format(
                cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f))
            sb.appendLine()

            val manager = contexto.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ch = manager.getCameraCharacteristics(idCam)

            // Nivel de hardware
            val nivel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            sb.appendLine("⚙️ Hardware level: ${nombreNivel(nivel)}")
            sb.appendLine()

            // Focales
            val focales = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            sb.appendLine("📐 Focales: ${focales?.joinToString(" / ") { "%.2fmm".format(it) } ?: "—"}")

            // Aperturas (diafragma)
            val aperturas = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            sb.appendLine("🕳 Aperturas: ${aperturas?.joinToString(" / ") { "f/%.1f".format(it) } ?: "—"}")
            val numAp = aperturas?.size ?: 0
            sb.appendLine("   → ${if (numAp > 1) "DIAFRAGMA VARIABLE ✓" else "diafragma FIJO (no controlable)"}")
            sb.appendLine()

            // Distancia mínima de enfoque
            val focoMin = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            if (focoMin != null && focoMin > 0f) {
                sb.appendLine("🔬 Enfoque mínimo: %.2f dioptrías ≈ %.1f cm".format(focoMin, 100f / focoMin))
            } else {
                sb.appendLine("🔬 Enfoque manual: no disponible (fijo)")
            }
            sb.appendLine()

            // ISO
            val rangoIso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            sb.appendLine("🎚 ISO: ${rangoIso?.lower ?: "?"} – ${rangoIso?.upper ?: "?"}")
            sb.appendLine()

            // Exposición
            val rangoExp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (rangoExp != null) {
                sb.appendLine("⏱ Exposición:")
                sb.appendLine("   mín: ${formatearNs(rangoExp.lower)}")
                sb.appendLine("   máx: ${formatearNs(rangoExp.upper)}")
                val maxSeg = rangoExp.upper / 1_000_000_000.0
                if (maxSeg >= 1.0) {
                    sb.appendLine("   ✓ permite exposiciones largas (≥1s)")
                } else {
                    sb.appendLine("   ⚠ máx %.2fs (no sirve para larga exposición)".format(maxSeg))
                }
            } else {
                sb.appendLine("⏱ Exposición: no disponible")
            }
            sb.appendLine()

            // Zoom
            val zoomMax = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            sb.appendLine("🔍 Zoom digital máx: ${zoomMax ?: "?"}x")
            sb.appendLine()

            // Lentes físicas dentro de la lógica
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val idsFisicos = ch.physicalCameraIds
                    sb.appendLine("📷 Lentes físicas dentro de esta cámara: ${idsFisicos.size}")
                    idsFisicos.forEach { idFis ->
                        try {
                            val chFis = manager.getCameraCharacteristics(idFis)
                            val foc = chFis.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            val ap = chFis.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                            sb.appendLine("   · ID \"$idFis\": focal ${foc?.joinToString(" / ") { "%.2fmm".format(it) } ?: "?"} · ${ap?.joinToString(" / ") { "f/%.1f".format(it) } ?: "?"}")
                        } catch (e: Exception) {
                            sb.appendLine("   · ID \"$idFis\": no legible (${e.message})")
                        }
                    }
                } catch (e: Exception) {
                    sb.appendLine("📷 Lentes físicas: no legible (${e.message})")
                }
            } else {
                sb.appendLine("📷 Lentes físicas: no soportado en Android <9")
            }

        } catch (e: Exception) {
            sb.appendLine()
            sb.appendLine("❌ Error leyendo características: ${e.message}")
        }

        return sb.toString()
    }

    private fun nombreNivel(nivel: Int?): String = when (nivel) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY (muy limitado)"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED (básico)"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL (completo)"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3 (máximo, control manual real ✓)"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "desconocido ($nivel)"
    }

    private fun formatearNs(ns: Long): String {
        if (ns <= 0) return "—"
        return if (ns < 1_000_000) {
            "1/${(1_000_000_000L / ns)}s"
        } else if (ns < 1_000_000_000L) {
            "%.2f ms".format(ns / 1_000_000.0)
        } else {
            "%.2f s".format(ns / 1_000_000_000.0)
        }
    }

    companion object {
        private const val TAG = "CamaraController"
    }
}