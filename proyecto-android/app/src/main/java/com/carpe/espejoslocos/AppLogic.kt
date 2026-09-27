package com.carpe.espejoslocos

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat

object AppLogic {

    private const val TAG = "EspejosLocos"
    private var imageView: ImageView? = null
    private var inicializado = false

    fun onIniciar(activity: MainActivity) {
        Log.e(TAG, "onIniciar")
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Ocultamos el PreviewView del generador.
        activity.findViewById<View>(R.id.vistaPreviaCamara)?.visibility = View.GONE

        // El generador de camara-tiempo-real ya pide el permiso solo.
        // En vez de competir con una segunda petición, esperamos a que lo
        // conceda y montamos. El retardo extra (900ms) da tiempo a que el
        // generador termine de vincular su PreviewView antes de que
        // nosotros tomemos el control.
        val handler = Handler(Looper.getMainLooper())
        var intentos = 0
        val runnable = object : Runnable {
            override fun run() {
                val concedido = ContextCompat.checkSelfPermission(
                    activity, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (concedido) {
                    if (!inicializado) {
                        handler.postDelayed({
                            if (!inicializado) montarMotor(activity)
                        }, 900)
                    }
                    return
                }
                if (intentos++ < 60) {
                    handler.postDelayed(this, 500)
                } else {
                    Toast.makeText(activity, "Sin permiso de cámara", Toast.LENGTH_LONG).show()
                }
            }
        }
        handler.post(runnable)
    }

    private fun montarMotor(activity: MainActivity) {
        if (inicializado) return
        inicializado = true
        Log.e(TAG, "montarMotor")

        val raiz = activity.findViewById<ViewGroup>(android.R.id.content)

        val iv = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        imageView = iv
        raiz.addView(iv)

        val ui = OverlayUI.crear(
            activity = activity,
            onCambiarFiltro = { filtro ->
                Toast.makeText(activity, "${filtro.icono}  ${filtro.nombre}", Toast.LENGTH_SHORT).show()
            },
            onCapturar = { capturar(activity) },
            onCambiarCamara = {
                MotorCamara.alternarCamara(activity, iv)
                Toast.makeText(
                    activity,
                    if (MotorCamara.usandoFrontal) "Frontal" else "Trasera",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        raiz.addView(ui)

        MotorCamara.iniciar(activity, iv)
    }

    private fun capturar(activity: MainActivity) {
        val iv = imageView ?: return
        val drawable = iv.drawable as? BitmapDrawable ?: run {
            Toast.makeText(activity, "Sin frame todavía", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = drawable.bitmap ?: return

        val nombre = "espejo_${System.currentTimeMillis()}.jpg"
        val valores = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EspejosLocos")
            }
        }
        val uri = activity.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores
        ) ?: run {
            Toast.makeText(activity, "No se pudo crear el archivo", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            activity.contentResolver.openOutputStream(uri)?.use { salida ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, salida)
            }
            Toast.makeText(activity, "Guardado en Pictures/EspejosLocos", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}