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
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.carpe.camara.R
import com.carpe.camara.data.CamaraController
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

    private lateinit var controller: CamaraController
    private var ultimoToqueSimple = 0L
    private val DOBLE_TOQUE_MS = 280L
    private val handler = Handler(Looper.getMainLooper())

    private var pulsacionLargaEjecutada = false
    private var capturaEnCola = false

    override fun onCreateView(
        inflater: LayoutInflater, contenedor: ViewGroup?, estado: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pantalla_camara, contenedor, false)

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

        controller = CamaraController(requireContext())

        controller.iniciar(viewLifecycleOwner, vistaPrevia) { camara ->
            actualizarHud(camara)
        }

        btnCapturar.setOnClickListener { lanzarCaptura() }
        btnAjustes.setOnClickListener { abrirAjustes() }
        btnGrid.setOnClickListener { alternarGrid() }
        imgMiniatura.setOnClickListener { abrirUltimaFoto() }

        configurarGestos()
        configurarTeclasVolumen(view)

        // Estado inicial del grid según lo guardado
        gridOverlay.visibility = if (controller.estado.mostrarGrid) View.VISIBLE else View.GONE
        txtLente.text = controller.estado.lente.etiqueta

        view.postDelayed({
            if (!isAdded) return@postDelayed
            if (controller.seleccionLenteRealDisponible) {
                mostrarToast("✓ 3 lentes físicas detectadas")
            } else {
                mostrarToast("⚠ Este móvil no expone lentes separadas · se usará zoom")
            }
        }, 1200)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configurarGestos() {
        vistaPrevia.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pulsacionLargaEjecutada = false
                    handler.postDelayed({
                        if (!pulsacionLargaEjecutada && isAdded) {
                            pulsacionLargaEjecutada = true
                            bloquearAEAF(event.x, event.y)
                        }
                    }, 600)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacksAndMessages(null)
                    if (pulsacionLargaEjecutada) {
                        pulsacionLargaEjecutada = false
                    } else {
                        val ahora = System.currentTimeMillis()
                        if (ahora - ultimoToqueSimple < DOBLE_TOQUE_MS) {
                            ciclarLente()
                            ultimoToqueSimple = 0L
                        } else {
                            ultimoToqueSimple = ahora
                            enfocarEn(event.x, event.y)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    pulsacionLargaEjecutada = false
                }
            }
            true
        }
    }

    private fun configurarTeclasVolumen(view: View) {
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                    keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    lanzarCaptura()
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    private fun actualizarHud(camara: androidx.camera.core.Camera) {
        txtModo.text = if (controller.estado.modo == ModoCaptura.AUTO)
            getString(R.string.modo_auto) else getString(R.string.modo_pro)
        txtLente.text = controller.estado.lente.etiqueta

        val zoomState = camara.cameraInfo.zoomState.value
        txtInfo.text = "%.1fx".format(zoomState?.zoomRatio ?: 1f)
    }

    private fun alternarGrid() {
        val nuevo = !controller.estado.mostrarGrid
        controller.aplicarEstado(
            controller.estado.copy(mostrarGrid = nuevo),
            viewLifecycleOwner,
            vistaPrevia
        )
        gridOverlay.visibility = if (nuevo) View.VISIBLE else View.GONE
        mostrarToast(if (nuevo) "Grid activada" else "Grid desactivada")
    }

    private fun lanzarCaptura() {
        if (capturaEnCola) return
        val segundos = controller.estado.temporizador.segundos
        if (segundos <= 0) capturar() else iniciarCuentaAtras(segundos)
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
            Log.e("PantallaCamara", "Error miniatura", e)
        }
    }

    private fun abrirUltimaFoto() {
        val uri = imgMiniatura.tag as? Uri ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        if (controller.estado.modo == ModoCaptura.PRO && controller.estado.focoManual) return
        val camera = controller.camaraActual() ?: return
        try {
            val factory = vistaPrevia.meteringPointFactory
            val punto = factory.createPoint(x, y)
            val accion = FocusMeteringAction.Builder(punto).build()
            camera.cameraControl.startFocusAndMetering(accion)
            txtBloqueo.visibility = View.GONE
        } catch (e: Exception) {
            Log.e("PantallaCamara", "Error enfoque", e)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun bloquearAEAF(x: Float, y: Float) {
        val camera = controller.camaraActual() ?: return
        try {
            val factory = vistaPrevia.meteringPointFactory
            val punto = factory.createPoint(x, y)
            val accion = FocusMeteringAction.Builder(
                punto,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).apply {
                setAutoCancelDuration(30, java.util.concurrent.TimeUnit.SECONDS)
            }.build()
            camera.cameraControl.startFocusAndMetering(accion)
            txtBloqueo.visibility = View.VISIBLE
            vibrar(120)
            handler.postDelayed({
                if (isAdded) txtBloqueo.visibility = View.GONE
            }, 3000)
        } catch (e: Exception) {
            Log.e("PantallaCamara", "Error bloqueo", e)
        }
    }

    private fun abrirAjustes() {
        val bottomSheet = BottomSheetDialog(requireContext())
        val vista = layoutInflater.inflate(R.layout.panel_ajustes, null)
        bottomSheet.setContentView(vista)
        PanelAjustes(
            vista = vista,
            controller = controller,
            cicloDeVida = viewLifecycleOwner,
            vistaPrevia = vistaPrevia,
            onCambio = { actualizarHudDesdeEstado() },
            onCerrar = { bottomSheet.dismiss() }
        ).configurar()
        bottomSheet.show()
    }

    private fun actualizarHudDesdeEstado() {
        txtModo.text = if (controller.estado.modo == ModoCaptura.AUTO)
            getString(R.string.modo_auto) else getString(R.string.modo_pro)
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
                val manager = requireContext().getSystemService(VibratorManager::class.java)
                manager.defaultVibrator
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
}