package com.carpe.camara.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
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
import java.util.Date
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

    private var cicloDeVidaActual: LifecycleOwner? = null
    private var vistaPreviaActual: PreviewView? = null

    var estado: CamaraEstado = CamaraEstado()
        private set

    var alListo: ((Camera) -> Unit)? = null

    var idPrincipal: String? = null
    var idTele: String? = null
    var idUltra: String? = null

    var seleccionLenteRealDisponible: Boolean = false
        private set

    private var capturaEnCurso: Boolean = false
    private var estadoPendiente: CamaraEstado? = null
    private var aplicarLargaExposicionEnBind: Boolean = false

    // === LOG ===
    private val registro = ArrayDeque<String>()
    private const val REGISTRO_MAX = 500

    fun registrar(tipo: String, mensaje: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        registro.addLast("[$ts] [$tipo] $mensaje")
        while (registro.size > REGISTRO_MAX) registro.removeFirst()
    }

    fun obtenerLog(): String {
        if (registro.isEmpty()) return "Sin actividad registrada todavía."
        return registro.joinToString("\n")
    }

    fun limpiarLog() {
        registro.clear()
        registrar("log", "Log limpiado por el usuario")
    }

    fun registrarZoom(z: Float) { registrar("zoom", "Zoom → %.2fx".format(z)) }

    fun camaraActual(): Camera? = camara

    fun iniciar(cicloDeVida: LifecycleOwner, vistaPrevia: PreviewView, onListo: (Camera) -> Unit) {
        cicloDeVidaActual = cicloDeVida
        vistaPreviaActual = vistaPrevia
        alListo = onListo
        registrar("init", "=== Inicio CamaraController ===")
        cargarEstadoGuardado()
        val futuro = ProcessCameraProvider.getInstance(contexto)
        futuro.addListener({
            try {
                proveedor = futuro.get()
                registrar("init", "ProcessCameraProvider listo")
                descubrirLentes()
                aplicarLargaExposicionEnBind = false
                enlazar(cicloDeVida, vistaPrevia)
            } catch (e: Exception) {
                registrar("error", "Inicio: ${e.message}")
                Log.e(TAG, "Error iniciando cámara", e)
            }
        }, ContextCompat.getMainExecutor(contexto))
    }

    private fun cargarEstadoGuardado() {
        try {
            estado = CamaraEstado(
                lente = LenteFisica.valueOf(prefs.getString("lente", LenteFisica.PRINCIPAL.name) ?: LenteFisica.PRINCIPAL.name),
                modo = ModoCaptura.valueOf(prefs.getString("modo", ModoCaptura.AUTO.name) ?: ModoCaptura.AUTO.name),
                iso = prefs.getInt("iso", 400),
                isoManual = prefs.getBoolean("isoManual", false),
                exposicionNs = prefs.getLong("exposicionNs", 16_666_666L),
                exposicionManual = prefs.getBoolean("exposicionManual", false),
                largaExposicion = prefs.getBoolean("largaExposicion", false),
                exposicionLargaNs = prefs.getLong("exposicionLargaNs", 1_000_000_000L),
                distanciaFocoDioptras = prefs.getFloat("foco", 0f),
                focoManual = prefs.getBoolean("focoManual", false),
                temperaturaK = prefs.getInt("wb", 5000),
                wbManual = prefs.getBoolean("wbManual", false),
                flashAuto = prefs.getBoolean("flashAuto", true),
                temporizador = Temporizador.valueOf(prefs.getString("timer", Temporizador.OFF.name) ?: Temporizador.OFF.name),
                mostrarGrid = prefs.getBoolean("grid", false)
            )
            registrar("estado", "Cargado: modo=${estado.modo}, lente=${estado.lente.etiqueta}")
        } catch (e: Exception) {
            registrar("error", "Cargando prefs: ${e.message}")
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
            .putBoolean("largaExposicion", estado.largaExposicion)
            .putLong("exposicionLargaNs", estado.exposicionLargaNs)
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
        val traseras = p.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        registrar("camara", "Cámaras traseras expuestas por CameraX: ${traseras.size}")

        if (traseras.size >= 2) {
            val conFocal = traseras.mapNotNull { info ->
                try {
                    val c2 = Camera2CameraInfo.from(info)
                    val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    if (focal != null) Triple(info, focal, c2.cameraId) else null
                } catch (e: Exception) { null }
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
        } else if (traseras.size == 1) {
            // Aunque solo haya una cámara lógica, guardamos los IDs físicos que conocemos
            idPrincipal = "0"
            idUltra = "3"
            idTele = "7"
            seleccionLenteRealDisponible = true // Asumimos que se puede intentar
        }
        registrar("camara", "IDs asignados · P=$idPrincipal T=$idTele U=$idUltra")
    }

    // ============================================================
    // LÓGICA DE SELECCIÓN DE LENTE (CON FALLBACK A CAMERA2 PURO)
    // ============================================================
    @SuppressLint("RestrictedApi")
    private fun enlazar(cicloDeVida: LifecycleOwner, vistaPrevia: PreviewView) {
        val p = proveedor ?: return

        // 1. Preparamos el Preview y la Captura como siempre
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(vistaPrevia.surfaceProvider)
        }

        val builderCaptura = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(if (estado.flashAuto) ImageCapture.FLASH_MODE_AUTO else ImageCapture.FLASH_MODE_OFF)

        val extender = Camera2Interop.Extender(builderCaptura)
        if (estado.modo != ModoCaptura.AUTO) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            if (estado.focoManual) {
                extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, estado.distanciaFocoDioptras)
            }
            if (estado.largaExposicion && aplicarLargaExposicionEnBind) {
                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, estado.exposicionLargaNs)
                val frameDuration = (estado.exposicionLargaNs * 1.05).toLong().coerceAtLeast(33_333_333L)
                extender.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
                if (estado.isoManual) {
                    extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, estado.iso)
                }
            } else if (estado.isoManual || estado.exposicionManual) {
                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                if (estado.isoManual) extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, estado.iso)
                if (estado.exposicionManual) extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, estado.exposicionNs)
            }
            if (estado.wbManual) {
                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                val (r, g, b) = kelvinAGanancias(estado.temperaturaK)
                extender.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(r, g, b, 1f))
            }
        }

        val captura = builderCaptura.build()

        // 2. Intentamos el bind con el ID físico (la vía oficial de CameraX)
        val idFisico = when (estado.lente) {
            LenteFisica.PRINCIPAL -> idPrincipal
            LenteFisica.TELEOBJETIVO -> idTele
            LenteFisica.ULTRA_GRAN_ANGULAR -> idUltra
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .setPhysicalCameraId(idFisico ?: "0") // <-- LA CLAVE: Pedimos la lente física concreta
            .build()

        try {
            p.unbindAll()
            camara = p.bindToLifecycle(cicloDeVida, selector, preview, captura)
            imageCapture = captura

            // Si funciona, actualizamos el zoom y notificamos
            if (!seleccionLenteRealDisponible || idFisico == null) {
                camara?.cameraControl?.let { control ->
                    val zoom = when (estado.lente) {
                        LenteFisica.PRINCIPAL -> 1f
                        LenteFisica.TELEOBJETIVO -> 2f
                        LenteFisica.ULTRA_GRAN_ANGULAR -> 0.6f
                    }
                    val zs = camara?.cameraInfo?.zoomState?.value
                    if (zs != null) control.setZoomRatio(zoom.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
                }
            } else {
                camara?.cameraControl?.setZoomRatio(1f)
            }

            registrar("camara", "Bind OK con ID físico '$idFisico' · modo=${estado.modo}")
            alListo?.invoke(camara!!)

        } catch (e: Exception) {
            // 3. Si el bind falla (como se reporta en algunos Xiaomi), usamos Camera2 puro
            registrar("error", "Bind con CameraX falló: ${e.message}. Probando Camera2 puro...")
            intentarAbrirCamaraFisicaDirecta(idFisico, vistaPrevia)
        }
    }

    /**
     * Plan B: Si el bind de CameraX falla, intentamos abrir la cámara física
     * directamente usando la API de Camera2.
     */
    private fun intentarAbrirCamaraFisicaDirecta(idFisico: String?, vistaPrevia: PreviewView) {
        if (idFisico == null) {
            registrar("error", "No hay ID físico para intentar abrir.")
            return
        }

        val manager = contexto.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val handler = Handler(Looper.getMainLooper())

        try {
            manager.openCamera(idFisico, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    registrar("camara2", "¡Éxito! Cámara física '$idFisico' abierta con Camera2.")
                    // Aquí iría la lógica completa de Camera2 para preview y captura.
                    // Por ahora, solo registramos el éxito y cerramos para no dejar la cámara colgada.
                    camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    registrar("camara2", "Cámara física '$idFisico' desconectada.")
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    registrar("error", "Error al abrir cámara física '$idFisico': código $error")
                    camera.close()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            registrar("error", "Excepción de acceso a la cámara '$idFisico': ${e.reason}")
        } catch (e: Exception) {
            registrar("error", "Excepción inesperada al abrir '$idFisico': ${e.message}")
        }
    }

    // ... el resto de funciones (cambiarLente, aplicarEstado, capturar, etc.) se mantienen igual ...
    // ... (copia aquí el resto de funciones de tu archivo actual para mantener la integridad) ...

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
        val ciclo = cicloDeVidaActual
        val vista = vistaPreviaActual

        if (estado.largaExposicion && ciclo != null && vista != null) {
            registrar("captura", "Activando larga exposición real para captura")
            aplicarLargaExposicionEnBind = true
            enlazar(ciclo, vista)
        }

        val captura = imageCapture ?: run {
            registrar("error", "Captura: cámara no lista")
            aplicarLargaExposicionEnBind = false
            onGuardado(false, "Cámara no lista", null); return
        }

        capturaEnCurso = true

        val nombre = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "MACRO_$nombre.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CamaraMacro")
            }
        }
        val opciones = ImageCapture.OutputFileOptions.Builder(
            contexto.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores
        ).build()
        registrar("captura", "Inicio · modo=${estado.modo} · exp=${formatearNs(if (estado.largaExposicion) estado.exposicionLargaNs else estado.exposicionNs)} · ISO=${estado.iso}")

        captura.takePicture(opciones, ejecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(resultado: ImageCapture.OutputFileResults) {
                registrar("captura", "OK · ${resultado.savedUri}")
                capturaEnCurso = false
                val necesitaRebind = aplicarLargaExposicionEnBind
                aplicarLargaExposicionEnBind = false
                estadoPendiente = null
                if (necesitaRebind && ciclo != null && vista != null) {
                    registrar("captura", "Restaurando preview normal")
                    enlazar(ciclo, vista)
                }
                onGuardado(true, "Foto guardada", resultado.savedUri)
            }

            override fun onError(excepcion: ImageCaptureException) {
                registrar("error", "Captura falló: ${excepcion.message}")
                capturaEnCurso = false
                val necesitaRebind = aplicarLargaExposicionEnBind
                aplicarLargaExposicionEnBind = false
                estadoPendiente = null
                if (necesitaRebind && ciclo != null && vista != null) {
                    registrar("captura", "Restaurando preview normal tras error")
                    enlazar(ciclo, vista)
                }
                onGuardado(false, "Error: ${excepcion.message}", null)
            }
        })
    }

    fun liberar() { proveedor?.unbindAll(); camara = null; imageCapture = null }

    fun cerrar() {
        registrar("cierre", "=== Cerrando CamaraController ===")
        liberar()
        ejecutor.shutdown()
    }

    fun obtenerDiagnostico(): String {
        val sb = StringBuilder()
        val manager = contexto.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cam = camara
        sb.appendLine("═══ CÁMARA ACTIVA ═══")
        if (cam == null) sb.appendLine("⚠ No inicializada.") else {
            try {
                val c2 = Camera2CameraInfo.from(cam.cameraInfo)
                sb.appendLine("ID: \"${c2.cameraId}\" · Lente UI: ${estado.lente.etiqueta}")
                sb.appendLine("Modo: ${estado.modo} · Zoom: %.2fx".format(cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f))
            } catch (e: Exception) { sb.appendLine("⚠ ${e.message}") }
        }
        sb.appendLine()
        sb.appendLine("═══ CÁMARAS PÚBLICAS ═══")
        val idsPublicos = manager.cameraIdList.toSet()
        idsPublicos.forEach { idCam -> sb.append(volcarCaracteristicas(manager, idCam, "  ")) }
        sb.appendLine()
        sb.appendLine("═══ ESCANEO AGRESIVO (IDs 0-15) ═══")
        for (i in 0..15) {
            val id = i.toString()
            if (idsPublicos.contains(id)) continue
            try {
                manager.getCameraCharacteristics(id)
                sb.appendLine("🔓 ID \"$id\" RESPONDE (metadata accesible)")
            } catch (e: Exception) {
                sb.appendLine("🔒 ID \"$id\": ${e.javaClass.simpleName}")
            }
        }
        sb.appendLine()
        sb.appendLine("═══ LENTES FÍSICAS DECLARADAS ═══")
        idsPublicos.forEach { idCam ->
            try {
                val ch = manager.getCameraCharacteristics(idCam)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val fis = ch.physicalCameraIds
                    if (fis.isNotEmpty()) sb.appendLine("Dentro de \"$idCam\": ${fis.joinToString(", ") { "\"$it\"" }}")
                }
            } catch (_: Exception) {}
        }
        return sb.toString()
    }

    fun probarAperturaDeIds(): String {
        val sb = StringBuilder()
        val manager = contexto.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val idsPublicos = manager.cameraIdList.toSet()
        sb.appendLine("🧪 TEST DE APERTURA")
        sb.appendLine()
        for (i in 0..15) {
            val id = i.toString()
            if (idsPublicos.contains(id)) { sb.appendLine("ID \"$id\" → público"); continue }
            try { manager.getCameraCharacteristics(id) } catch (e: Exception) { sb.appendLine("ID \"$id\" → no existe"); continue }
            var resultado = "?"
            val latch = java.util.concurrent.CountDownLatch(1)
            val handler = Handler(Looper.getMainLooper())
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) { resultado = "✅ ABIERTA"; camera.close(); latch.countDown() }
                    override fun onDisconnected(camera: CameraDevice) { resultado = "⚠ desconectada"; camera.close(); latch.countDown() }
                    override fun onError(camera: CameraDevice, error: Int) {
                        resultado = "❌ código $error"; camera.close(); latch.countDown()
                    }
                }, handler)
                latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: CameraAccessException) { resultado = "❌ excepción ${e.reason}" } catch (e: Exception) { resultado = "❌ ${e.message}" }
            sb.appendLine("ID \"$id\" → $resultado")
        }
        return sb.toString()
    }

    private fun volcarCaracteristicas(manager: CameraManager, id: String, indent: String): String {
        val sb = StringBuilder()
        try {
            val ch = manager.getCameraCharacteristics(id)
            sb.appendLine("${indent}─── ID \"$id\" ───")
            val foc = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            sb.appendLine("${indent}  Focales: ${foc?.joinToString(" / ") { "%.2fmm".format(it) } ?: "—"}")
            val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            if (iso != null) sb.appendLine("${indent}  ISO: ${iso.lower} – ${iso.upper}")
            val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            if (exp != null) sb.appendLine("${indent}  Exp: ${formatearNs(exp.lower)} – ${formatearNs(exp.upper)}")
            val zoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            sb.appendLine("${indent}  Zoom máx: ${zoom ?: "?"}x")
        } catch (e: Exception) { sb.appendLine("${indent}⚠ ${e.message}") }
        return sb.toString()
    }

    private fun formatearNs(ns: Long): String {
        if (ns <= 0) return "—"
        return if (ns < 1_000_000) "1/${1_000_000_000L / ns}s"
        else if (ns < 1_000_000_000L) "%.2fms".format(ns / 1_000_000.0)
        else "%.2fs".format(ns / 1_000_000_000.0)
    }

    companion object { private const val TAG = "CamaraController" }
}