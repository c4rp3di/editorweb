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
                mostrarGrid = prefs.getBoolean("grid", false),
                exposicionLargaNs = prefs.getLong("exposicionLargaNs", 1_000_000_000L)
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
            .putLong("exposicionLargaNs", estado.exposicionLargaNs)
            .apply()
    }

    private fun descubrirLentes() {
        val p = proveedor ?: return
        val traseras = p.availableCameraInfos.filter {
            it.lensFacing == CameraSelector.LENS_FACING_BACK
        }
        Log.d(TAG, "Cámaras traseras listadas por CameraX: ${traseras.size}")

        // Si el sistema expone varias lentes, las ordenamos por focal para asignar IDs
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
            conFocal.size == 1 -> {
                idPrincipal = conFocal.first().third
                seleccionLenteRealDisponible = false
            }
        }
        Log.d(TAG, "Lentes detectadas · P=$idPrincipal T=$idTele U=$idUltra · Real=$seleccionLenteRealDisponible")
    }

    private fun selectorParaLente(lente: LenteFisica): CameraSelector {
        val idFisico = when (lente) {
            LenteFisica.PRINCIPAL -> idPrincipal
            LenteFisica.TELEOBJETIVO -> idTele
            LenteFisica.ULTRA_GRAN_ANGULAR -> idUltra
        }
        // Si no tenemos un ID específico o no se soporta la selección real, usamos la cámara trasera por defecto
        if (idFisico == null || !seleccionLenteRealDisponible) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        // Intentamos seleccionar la cámara física específica
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .setPhysicalCameraId(idFisico) // <-- La clave para forzar la lente
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

        // --- Lógica para modo PRO y LARGA EXPOSICIÓN ---
        if (estado.modo == ModoCaptura.PRO || estado.modo == ModoCaptura.LARGA_EXPOSICION) {
            // Desactivamos el control automático de enfoque y exposición para tomar el control
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

            // Aplicamos el foco manual si está activado
            if (estado.focoManual) {
                extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, estado.distanciaFocoDioptras)
            }

            // Aplicamos ISO y exposición manual si están activados
            if (estado.isoManual) {
                extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, estado.iso)
            }
            if (estado.exposicionManual) {
                extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, estado.exposicionNs)
            }
            
            // Lógica específica para LARGA EXPOSICIÓN: forzamos el tiempo de exposición
            if (estado.modo == ModoCaptura.LARGA_EXPOSICION) {
                extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, estado.exposicionLargaNs)
            }

            // Aplicamos balance de blancos manual si está activado
            if (estado.wbManual) {
                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                val (r, g, b) = kelvinAGanancias(estado.temperaturaK)
                extender.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(r, g, g, b))
            }
        }

        val captura = builderCaptura.build()
        val selector = selectorParaLente(estado.lente)

        try {
            p.unbindAll()
            camara = p.bindToLifecycle(cicloDeVida, selector, preview, captura)
            imageCapture = captura

            // Ajustamos el zoom solo si no tenemos selección real de lente
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
        val r: Float; val g: Float; val b: Float
        if (t <= 66f) {
            r = 1f
            g = ((99.4708025861 * Math.log(t.toDouble()) - 161.1195681661) / 255.0).toFloat().coerceIn(0f, 1f)
        } else {
            r = ((329.698727446 * Math.pow(t - 60.0, -0.1332047592)) / 255.0).toFloat().coerceIn(0f, 1f)
            g = ((288.1221695283 * Math.pow(t - 60.0, -0.0755148492)) / 255.0).toFloat().coerceIn(0f, 1f)
        }
        b = when {
            t >= 66f -> 1f
            t <= 19f -> 0f
            else -> ((138.5177312231 * Math.log(t - 10.0) - 305.0447927307) / 255.0).toFloat().coerceIn(0f, 1f)
        }
        return Triple(r.coerceAtLeast(0.1f), g.coerceAtLeast(0.1f), b.coerceAtLeast(0.1f))
    }

    fun capturar(onGuardado: (Boolean, String, Uri?) -> Unit) {
        val captura = imageCapture ?: run {
            onGuardado(false, "Cámara no lista", null)
            return
        }
        val nombre = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
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
    // DIAGNÓSTICO AMPLIADO (Fase 1)
    // ============================================================
    fun obtenerDiagnostico(): String {
        val sb = StringBuilder()
        val manager = contexto.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cam = camara

        sb.appendLine("═══════════════════════════════════")
        sb.appendLine("📸 CÁMARA ACTIVA AHORA")
        sb.appendLine("═══════════════════════════════════")

        if (cam == null) {
            sb.appendLine("⚠ No inicializada todavía.")
        } else {
            try {
                val c2Info = Camera2CameraInfo.from(cam.cameraInfo)
                sb.appendLine("ID CameraX activo: \"${c2Info.cameraId}\"")
                sb.appendLine("Lente UI: ${estado.lente.etiqueta}")
                sb.appendLine("Zoom actual: %.2fx".format(cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f))
            } catch (e: Exception) {
                sb.appendLine("⚠ No se pudo leer la cámara activa: ${e.message}")
            }
        }
        sb.appendLine()

        sb.appendLine("═══════════════════════════════════")
        sb.appendLine("📷 TODAS LAS CÁMARAS DEL SISTEMA")
        sb.appendLine("═══════════════════════════════════")

        try {
            val ids = manager.cameraIdList
            sb.appendLine("Total detectadas: ${ids.size}")
            sb.appendLine()

            ids.forEach { idCam ->
                try {
                    val ch = manager.getCameraCharacteristics(idCam)
                    sb.appendLine("─── ID \"$idCam\" ─────────────────")
                    sb.appendLine("  Facing: ${nombreFacing(ch.get(CameraCharacteristics.LENS_FACING))}")
                    sb.appendLine("  Hardware level: ${nombreNivel(ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
                    val foc = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    sb.appendLine("  Focales: ${foc?.joinToString(" / ") { "%.2fmm".format(it) } ?: "—"}")
                    val ap = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    sb.appendLine("  Aperturas: ${ap?.joinToString(" / ") { "f/%.1f".format(it) } ?: "—"}")
                    val tamSensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (tamSensor != null) sb.appendLine("  Tamaño sensor: %.2f × %.2f mm".format(tamSensor.width, tamSensor.height))
                    val tamPx = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    if (tamPx != null) sb.appendLine("  Resolución sensor: ${tamPx.width} × ${tamPx.height} (%.1f MP)".format((tamPx.width.toLong() * tamPx.height / 1_000_000.0)))
                    val rangoIso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    if (rangoIso != null) sb.appendLine("  ISO: ${rangoIso.lower} – ${rangoIso.upper}")
                    val rangoExp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    if (rangoExp != null) sb.appendLine("  Exposición: ${formatearNs(rangoExp.lower)} – ${formatearNs(rangoExp.upper)}")
                    val focoMin = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    if (focoMin != null && focoMin > 0f) sb.appendLine("  Enfoque mín: %.2f diop → %.1f cm".format(focoMin, 100f / focoMin)) else sb.appendLine("  Enfoque: fijo (sin AF manual)")
                    val modosAf = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    if (modosAf != null) sb.appendLine("  Modos AF: ${modosAf.joinToString(", ") { nombreModoAf(it) }}")
                    val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    sb.appendLine("  OIS: ${if (ois?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) "✓" else "✗"}")
                    val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                    sb.appendLine("  Flash: ${if (flash == true) "✓" else "✗"}")
                    val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    val sopRaw = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
                    val sopLogical = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
                    sb.appendLine("  RAW (DNG): ${if (sopRaw) "✓" else "✗"}")
                    sb.appendLine("  Cámara lógica multi-lente: ${if (sopLogical) "✓" else "✗"}")
                    val zoomMax = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    sb.appendLine("  Zoom digital máx: ${zoomMax ?: "?"}x")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try {
                            val idsFis = ch.physicalCameraIds
                            if (idsFis.isNotEmpty()) sb.appendLine("  Físicas dentro: ${idsFis.joinToString(", ") { "\"$it\"" }}")
                        } catch (_: Exception) {}
                    }
                    sb.appendLine()
                } catch (e: Exception) {
                    sb.appendLine("  ⚠ ID \"$idCam\" no legible: ${e.message}").appendLine()
                }
            }
        } catch (e: Exception) {
            sb.appendLine("❌ Error listando cámaras: ${e.message}")
        }
        return sb.toString()
    }

    private fun nombreFacing(f: Int?): String = when (f) {
        CameraCharacteristics.LENS_FACING_BACK -> "TRASERA"
        CameraCharacteristics.LENS_FACING_FRONT -> "frontal"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "externa"
        else -> "?"
    }

    private fun nombreNivel(nivel: Int?): String = when (nivel) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "?"
    }

    private fun nombreModoAf(m: Int): String = when (m) {
        CaptureRequest.CONTROL_AF_MODE_OFF -> "OFF"
        CaptureRequest.CONTROL_AF_MODE_AUTO -> "AUTO"
        CaptureRequest.CONTROL_AF_MODE_MACRO -> "MACRO"
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONT-VIDEO"
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONT-PIC"
        CaptureRequest.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "?($m)"
    }

    private fun formatearNs(ns: Long): String {
        if (ns <= 0) return "—"
        return if (ns < 1_000_000) {
            "1/${(1_000_000_000L / ns)}s"
        } else if (ns < 1_000_000_000L) {
            "%.2fms".format(ns / 1_000_000.0)
        } else {
            "%.2fs".format(ns / 1_000_000_000.0)
        }
    }

    companion object {
        private const val TAG = "CamaraController"
    }
}