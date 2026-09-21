package com.carpe.camara.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.carpe.camara.R
import com.carpe.camara.data.CamaraController
import com.carpe.camara.data.CamaraEstado
import com.carpe.camara.data.LenteFisica
import com.carpe.camara.data.ModoCaptura
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.fragment.app.Fragment
import java.io.InputStream

class PantallaCamara : Fragment() {

    private lateinit var vistaPrevia: PreviewView
    private lateinit var gridOverlay: GridOverlay
    private lateinit var btnCapturar: Button
    private lateinit var btnAjustes: Button
    private lateinit var btnGrid: Button
    private lateinit var txtModo: TextView
    private lateinit var txtLente: TextView
    private lateinit var txtInfo: TextView
    private lateinit var txtToast: TextView
    private lateinit var txtContador: TextView
    private lateinit var txtBloqueo: TextView
    private lateinit var imgMiniatura: ImageView

    private lateinit var floatingPanel: LinearLayout
    private lateinit var rowIsoFloat: LinearLayout
    private lateinit var rowExpFloat: LinearLayout
    private lateinit var rowLargaFloat: LinearLayout
    private lateinit var rowFocoFloat: LinearLayout
    private lateinit var rowWbFloat: LinearLayout
    private lateinit var sliderIsoFloat: SeekBar
    private lateinit var sliderExpFloat: SeekBar
    private lateinit var sliderLargaFloat: SeekBar
    private lateinit var sliderFocoFloat: SeekBar
    private lateinit var sliderWbFloat: SeekBar
    private lateinit var valorIsoFloat: TextView
    private lateinit var valorExpFloat: TextView
    private lateinit var valorLargaFloat: TextView
    private lateinit var valorFocoFloat: TextView
    private lateinit var valorWbFloat: TextView
    private lateinit var floatingPin: Button
    private lateinit var floatingClose: Button

    private lateinit var controller: CamaraController
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector
    private val handler = Handler(Looper.getMainLooper())

    private var capturaEnCola = false
    private var floatPinned = false
    private var ignorarCambiosSlider = false

    private val autoHideRunnable = Runnable { ocultarPanelFlotante() }

    override fun onCreateView(inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?): View =
        inflater.inflate(R.layout.fragment_pantalla_camara, contenedor, false)

    override fun onViewCreated(view: View, estado: Bundle?) {
        super.onViewCreated(view, estado)

        vistaPrevia = view.findViewById(R.id.vistaPrevia)
        gridOverlay = view.findViewById(R.id.gridOverlay)
        btnCapturar = view.findViewById(R.id.btnCapturar)
        btnAjustes = view.findViewById(R.id.btnAjustes)
        btnGrid = view.findViewById(R.id.btnGrid)
        txtModo = view.findViewById(R.id.txtModo)
        txtLente = view.findViewById(R.id.txtLente)
        txtInfo = view.findViewById(R.id.txtInfo)
        txtToast = view.findViewById(R.id.txtToast)
        txtContador = view.findViewById(R.id.txtContador)
        txtBloqueo = view.findViewById(R.id.txtBloqueo)
        imgMiniatura = view.findViewById(R.id.imgMiniatura)

        floatingPanel = view.findViewById(R.id.floatingPanel)
        rowIsoFloat = view.findViewById(R.id.rowIsoFloat)
        rowExpFloat = view.findViewById(R.id.rowExpFloat)
        rowLargaFloat = view.findViewById(R.id.rowLargaFloat)
        rowFocoFloat = view.findViewById(R.id.rowFocoFloat)
        rowWbFloat = view.findViewById(R.id.rowWbFloat)
        sliderIsoFloat = view.findViewById(R.id.sliderIsoFloat)
        sliderExpFloat = view.findViewById(R.id.sliderExpFloat)
        sliderLargaFloat = view.findViewById(R.id.sliderLargaFloat)
        sliderFocoFloat = view.findViewById(R.id.sliderFocoFloat)
        sliderWbFloat = view.findViewById(R.id.sliderWbFloat)
        valorIsoFloat = view.findViewById(R.id.valorIsoFloat)
        valorExpFloat = view.findViewById(R.id.valorExpFloat)
        valorLargaFloat = view.findViewById(R.id.valorLargaFloat)
        valorFocoFloat = view.findViewById(R.id.valorFocoFloat)
        valorWbFloat = view.findViewById(R.id.valorWbFloat)
        floatingPin = view.findViewById(R.id.floatingPin)
        floatingClose = view.findViewById(R.id.floatingClose)

        configurarPanelFlotante()

        controller = CamaraController(requireContext())
        controller.iniciar(viewLifecycleOwner, vistaPrevia) { camara -> actualizarHud(camara) }

        btnCapturar.setOnClickListener { lanzarCaptura() }
        btnAjustes.setOnClickListener { abrirAjustes() }
        btnGrid.setOnClickListener { alternarGrid() }
        imgMiniatura.setOnClickListener { abrirUltimaFoto() }

        configurarGestos()
        configurarTeclasVolumen(view)

        gridOverlay.visibility = if (controller.estado.mostrarGrid) View.VISIBLE else View.GONE
        txtLente.text = controller.estado.lente.etiqueta

        view.postDelayed({
            if (!isAdded) return@postDelayed
            if (controller.seleccionLenteRealDisponible) mostrarToast("✓ Cámara multi-lente lista")
            else mostrarToast("ℹ Zoom controla la lente")
        }, 1200)
    }

    // ============================================================
    // PANEL FLOTANTE DE SLIDERS
    // ============================================================
    private fun configurarPanelFlotante() {
        floatingPin.setOnClickListener {
            floatPinned = !floatPinned
            floatingPin.text = if (floatPinned) "📍" else "📌"
            if (floatPinned) handler.removeCallbacks(autoHideRunnable)
            else programarAutoHide()
            mostrarToast(if (floatPinned) "Panel fijado" else "Panel se auto-ocultará")
        }

        floatingClose.setOnClickListener { ocultarPanelFlotante() }

        sliderIsoFloat.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valorIsoFloat.text = (p + 50).toString()
                if (fromUser) resetAutoHide()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { handler.removeCallbacks(autoHideRunnable) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (ignorarCambiosSlider) return
                val iso: Int = (sliderIsoFloat.progress + 50).coerceIn(50, 12800)
                aplicar(controller.estado.copy(iso = iso, isoManual = true))
                programarAutoHide()
            }
        })

        sliderExpFloat.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valorExpFloat.text = nsATexto(nsDesdeProgress(p))
                if (fromUser) resetAutoHide()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { handler.removeCallbacks(autoHideRunnable) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (ignorarCambiosSlider) return
                val ns: Long = nsDesdeProgress(sliderExpFloat.progress)
                aplicar(controller.estado.copy(
                    exposicionNs = ns,
                    exposicionManual = true,
                    largaExposicion = false
                ))
                programarAutoHide()
            }
        })

        sliderLargaFloat.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valorLargaFloat.text = segundosATexto(segundosDesdeProgressLarga(p))
                if (fromUser) resetAutoHide()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { handler.removeCallbacks(autoHideRunnable) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (ignorarCambiosSlider) return
                val segundos: Double = segundosDesdeProgressLarga(sliderLargaFloat.progress)
                val nanos: Long = (segundos * 1_000_000_000.0).toLong()
                aplicar(controller.estado.copy(
                    exposicionLargaNs = nanos,
                    largaExposicion = true,
                    exposicionManual = false
                ))
                programarAutoHide()
            }
        })

        sliderFocoFloat.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valorFocoFloat.text = dioptrasATexto(p / 100f * 10f)
                if (fromUser) resetAutoHide()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { handler.removeCallbacks(autoHideRunnable) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (ignorarCambiosSlider) return
                val d: Float = sliderFocoFloat.progress / 100f * 10f
                aplicar(controller.estado.copy(
                    distanciaFocoDioptras = d,
                    focoManual = true
                ))
                programarAutoHide()
            }
        })

        valorFocoFloat.setOnClickListener {
            val actual = controller.estado.distanciaFocoDioptras
            val edit = EditText(requireContext()).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("%.2f".format(actual))
                setSelection(text.length)
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Foco fino (dioptrías)")
                .setMessage("0 = ∞ · 10 = ~10cm\nRango: 0.00 – 10.00")
                .setView(edit)
                .setPositiveButton("Aplicar") { _, _ ->
                    val txt = edit.text.toString().replace(',', '.')
                    val v = txt.toFloatOrNull()
                    if (v != null && v in 0f..10f) {
                        sliderFocoFloat.progress = (v / 10f * 100).toInt().coerceIn(0, 100)
                        valorFocoFloat.text = dioptrasATexto(v)
                        aplicar(controller.estado.copy(distanciaFocoDioptras = v, focoManual = true))
                    }
                }
                .setNegativeButton("Cancelar", null).show()
        }

        sliderWbFloat.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                valorWbFloat.text = "${p + 2000}K"
                if (fromUser) resetAutoHide()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { handler.removeCallbacks(autoHideRunnable) }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (ignorarCambiosSlider) return
                val k: Int = sliderWbFloat.progress + 2000
                aplicar(controller.estado.copy(temperaturaK = k, wbManual = true))
                programarAutoHide()
            }
        })
    }

    private fun actualizarPanelFlotante() {
        val e = controller.estado
        val debeMostrar = e.modo != ModoCaptura.AUTO &&
            (e.isoManual || e.exposicionManual || e.largaExposicion || e.focoManual || e.wbManual)

        if (!debeMostrar) { ocultarPanelFlotante(); return }

        ignorarCambiosSlider = true

        rowIsoFloat.visibility = if (e.isoManual) View.VISIBLE else View.GONE
        if (e.isoManual) {
            sliderIsoFloat.progress = (e.iso - 50).coerceIn(0, 12750)
            valorIsoFloat.text = e.iso.toString()
        }

        rowExpFloat.visibility = if (e.exposicionManual && !e.largaExposicion) View.VISIBLE else View.GONE
        if (e.exposicionManual && !e.largaExposicion) {
            sliderExpFloat.progress = progressDesdeNs(e.exposicionNs)
            valorExpFloat.text = nsATexto(e.exposicionNs)
        }

        rowLargaFloat.visibility = if (e.largaExposicion) View.VISIBLE else View.GONE
        if (e.largaExposicion) {
            val seg: Double = e.exposicionLargaNs.toDouble() / 1_000_000_000.0
            sliderLargaFloat.progress = progressDesdeSegundosLarga(seg)
            valorLargaFloat.text = segundosATexto(seg)
        }

        rowFocoFloat.visibility = if (e.focoManual) View.VISIBLE else View.GONE
        if (e.focoManual) {
            sliderFocoFloat.progress = (e.distanciaFocoDioptras / 10f * 100).toInt().coerceIn(0, 100)
            valorFocoFloat.text = dioptrasATexto(e.distanciaFocoDioptras)
        }

        rowWbFloat.visibility = if (e.wbManual) View.VISIBLE else View.GONE
        if (e.wbManual) {
            sliderWbFloat.progress = (e.temperaturaK - 2000).coerceIn(0, 6000)
            valorWbFloat.text = "${e.temperaturaK}K"
        }

        ignorarCambiosSlider = false

        floatingPanel.visibility = View.VISIBLE
        programarAutoHide()
    }

    private fun programarAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        if (!floatPinned) handler.postDelayed(autoHideRunnable, 3000)
    }

    private fun resetAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        if (!floatPinned) handler.postDelayed(autoHideRunnable, 3000)
    }

    private fun ocultarPanelFlotante() {
        floatingPanel.visibility = View.GONE
        handler.removeCallbacks(autoHideRunnable)
    }

    private fun nsATexto(ns: Long): String {
        val ms = ns / 1_000_000.0
        return if (ms < 1.0) "1/${(1000.0 / ms).toInt().coerceAtLeast(1)}"
        else "%.1fs".format(ms / 1000.0)
    }
    private fun progressDesdeNs(ns: Long): Int {
        val min = 125_000.0
        val max = 2_000_000_000.0
        val v = ns.toDouble().coerceIn(min, max)
        return ((Math.log(v / min) / Math.log(max / min)) * 100.0).toInt().coerceIn(0, 100)
    }
    private fun nsDesdeProgress(p: Int): Long {
        val min = 125_000.0
        val max = 2_000_000_000.0
        val v: Double = min * Math.pow(max / min, p / 100.0)
        return v.toLong()
    }
    private fun segundosDesdeProgressLarga(p: Int): Double {
        return 1.0 * Math.pow(30.0, p / 100.0)
    }
    private fun progressDesdeSegundosLarga(seg: Double): Int {
        val v = seg.coerceIn(1.0, 30.0)
        return ((Math.log(v) / Math.log(30.0)) * 100.0).toInt().coerceIn(0, 100)
    }
    private fun segundosATexto(s: Double): String =
        if (s < 10) "%.1fs".format(s) else "%.0fs".format(s)

    private fun dioptrasATexto(d: Float): String =
        if (d <= 0.05f) "∞" else "%.2f (%.0fcm)".format(d, 100f / d)

    // ============================================================
    // GESTOS
    // ============================================================
    @SuppressLint("ClickableViewAccessibility")
    private fun configurarGestos() {
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (floatingPanel.visibility != View.VISIBLE) {
                    val e2 = controller.estado
                    if (e2.isoManual || e2.exposicionManual || e2.largaExposicion || e2.focoManual || e2.wbManual) {
                        actualizarPanelFlotante()
                        return true
                    }
                }
                enfocarEn(e.x, e.y)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                ciclarLente()
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (e.pointerCount > 1) return
                bloquearAEAF(e.x, e.y)
            }
        })

        scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val camera = controller.camaraActual() ?: return false
                val zs = camera.cameraInfo.zoomState.value ?: return false
                val nuevo: Float = (zs.zoomRatio * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                camera.cameraControl.setZoomRatio(nuevo)
                txtInfo.text = "%.1fx".format(nuevo)
                controller.registrarZoom(nuevo)
                return true
            }
        })

        vistaPrevia.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun configurarTeclasVolumen(view: View) {
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    lanzarCaptura()
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    private fun actualizarHud(camara: androidx.camera.core.Camera) {
        txtModo.text = when (controller.estado.modo) {
            ModoCaptura.AUTO -> getString(R.string.modo_auto)
            ModoCaptura.PRO -> getString(R.string.modo_pro)
            ModoCaptura.LARGA_EXPOSICION -> "LARGA"
        }
        txtLente.text = controller.estado.lente.etiqueta
        val zs = camara.cameraInfo.zoomState.value
        txtInfo.text = "%.1fx".format(zs?.zoomRatio ?: 1f)
    }

    private fun alternarGrid() {
        val nuevo = !controller.estado.mostrarGrid
        controller.aplicarEstado(controller.estado.copy(mostrarGrid = nuevo), viewLifecycleOwner, vistaPrevia)
        gridOverlay.visibility = if (nuevo) View.VISIBLE else View.GONE
        mostrarToast(if (nuevo) "Grid activada" else "Grid desactivada")
    }

    private fun lanzarCaptura() {
        if (capturaEnCola) return
        val s = controller.estado.temporizador.segundos
        if (s <= 0) capturar() else iniciarCuentaAtras(s)
    }

    private fun iniciarCuentaAtras(segundos: Int) {
        capturaEnCola = true
        btnCapturar.isEnabled = false
        txtContador.visibility = View.VISIBLE
        var restante = segundos
        val runnable = object : Runnable {
            override fun run() {
                if (!isAdded) return
                txtContador.text = restante.toString()
                vibrar(50)
                beep()
                if (restante <= 0) {
                    txtContador.visibility = View.GONE
                    capturaEnCola = false
                    btnCapturar.isEnabled = true
                    capturar()
                } else {
                    restante--
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(runnable)
    }

    private fun capturar() {
        vibrar(80)
        controller.capturar { ok, mensaje, uri ->
            requireActivity().runOnUiThread {
                if (ok) {
                    mostrarToast(getString(R.string.guardado_ok))
                    if (uri != null) actualizarMiniatura(uri)
                } else mostrarToast(mensaje)
            }
        }
    }

    private fun actualizarMiniatura(uri: Uri) {
        try {
            val stream: InputStream? = requireContext().contentResolver.openInputStream(uri)
            val bmp: Bitmap? = BitmapFactory.decodeStream(stream)
            stream?.close()
            if (bmp != null) {
                imgMiniatura.setImageBitmap(bmp)
                imgMiniatura.visibility = View.VISIBLE
                imgMiniatura.tag = uri
            }
        } catch (e: Exception) {
            Log.e("PantallaCamara", "Miniatura", e)
        }
    }

    private fun abrirUltimaFoto() {
        val uri = imgMiniatura.tag as? Uri ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newRawUri("", uri)
            }
            startActivity(intent)
        } catch (e: Exception) {
            mostrarToast("No hay app de galería disponible")
        }
    }

    private fun ciclarLente() {
        val nueva = when (controller.estado.lente) {
            LenteFisica.PRINCIPAL -> LenteFisica.TELEOBJETIVO
            LenteFisica.TELEOBJETIVO -> LenteFisica.ULTRA_GRAN_ANGULAR
            LenteFisica.ULTRA_GRAN_ANGULAR -> LenteFisica.PRINCIPAL
        }
        controller.cambiarLente(nueva, viewLifecycleOwner, vistaPrevia)
        txtLente.text = nueva.etiqueta
        mostrarToast("Lente: ${nueva.etiqueta}")
    }

    private fun enfocarEn(x: Float, y: Float) {
        if (controller.estado.modo != ModoCaptura.AUTO && controller.estado.focoManual) return
        val camera = controller.camaraActual() ?: return
        try {
            val punto = vistaPrevia.meteringPointFactory.createPoint(x, y)
            camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(punto).build())
            txtBloqueo.visibility = View.GONE
        } catch (e: Exception) {
            Log.e("PantallaCamara", "Enfoque", e)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun bloquearAEAF(x: Float, y: Float) {
        val camera = controller.camaraActual() ?: return
        try {
            val punto = vistaPrevia.meteringPointFactory.createPoint(x, y)
            val accion = FocusMeteringAction.Builder(punto, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .apply { setAutoCancelDuration(30, java.util.concurrent.TimeUnit.SECONDS) }
                .build()
            camera.cameraControl.startFocusAndMetering(accion)
            txtBloqueo.visibility = View.VISIBLE
            vibrar(120)
            handler.postDelayed({ if (isAdded) txtBloqueo.visibility = View.GONE }, 3000)
        } catch (e: Exception) {
            Log.e("PantallaCamara", "Bloqueo", e)
        }
    }

    private fun abrirAjustes() {
        val bs = BottomSheetDialog(requireContext())
        val v = layoutInflater.inflate(R.layout.panel_ajustes, null)
        bs.setContentView(v)
        PanelAjustes(
            vista = v,
            controller = controller,
            cicloDeVida = viewLifecycleOwner,
            vistaPrevia = vistaPrevia,
            onCambio = {
                actualizarHudDesdeEstado()
                actualizarPanelFlotante()
            },
            onCerrar = { bs.dismiss() }
        ).configurar()
        bs.show()
    }

    private fun actualizarHudDesdeEstado() {
        txtModo.text = when (controller.estado.modo) {
            ModoCaptura.AUTO -> getString(R.string.modo_auto)
            ModoCaptura.PRO -> getString(R.string.modo_pro)
            ModoCaptura.LARGA_EXPOSICION -> "LARGA"
        }
        txtLente.text = controller.estado.lente.etiqueta
        gridOverlay.visibility = if (controller.estado.mostrarGrid) View.VISIBLE else View.GONE
    }

    private fun mostrarToast(mensaje: String) {
        txtToast.text = mensaje
        txtToast.visibility = View.VISIBLE
        txtToast.postDelayed({ txtToast.visibility = View.GONE }, 1600)
    }

    private fun vibrar(ms: Long) {
        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requireContext().getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                requireContext().getSystemService(Vibrator::class.java)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            handler.postDelayed({ tone.release() }, 200)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        view?.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        controller.cerrar()
    }

    private fun aplicar(nuevo: CamaraEstado) {
        controller.aplicarEstado(nuevo, viewLifecycleOwner, vistaPrevia)
        actualizarHudDesdeEstado()
    }
}