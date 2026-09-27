package com.carpe.espejoslocos

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Punto de entrada de la app. MainActivity llama a onIniciar() al final
 * de su onCreate(), y desde aquí montamos todo el motor de filtros.
 */
object AppLogic {

    private var imageView: ImageView? = null
    private var inicializado = false

    fun onIniciar(activity: MainActivity) {
        if (inicializado) return

        // Ocultamos el PreviewView del generador: no lo usamos.
        activity.findViewById<View>(R.id.vistaPreviaCamara)?.visibility = View.GONE

        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val lanzador = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { concedido ->
            if (concedido) montarMotor(activity)
            else Toast.makeText(activity, "Sin permiso de cámara no se puede usar la app", Toast.LENGTH_LONG).show()
        }

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            montarMotor(activity)
        } else {
            lanzador.launch(Manifest.permission.CAMERA)
        }
    }

    private fun montarMotor(activity: MainActivity) {
        if (inicializado) return
        inicializado = true

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
                    if (MotorCamara.usandoFrontal) "Cámara frontal" else "Cámara trasera",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        raiz.addView(ui)

        MotorCamara.iniciar(activity, iv)
    }

    private fun capturar(activity: MainActivity) {
        val iv = imageView ?: return
        val drawable = iv.drawable as? BitmapDrawable ?: return
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
        )
        if (uri == null) {
            Toast.makeText(activity, "No se pudo crear el archivo", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            activity.contentResolver.openOutputStream(uri)?.use { salida ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, salida)
            }
            Toast.makeText(activity, "📸 Guardado en Pictures/EspejosLocos", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}