package com.carpe.espejoslocos

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.carpe.espejoslocos.AppLogic
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import org.opencv.android.OpenCVLoader
// [IMPORTS:FUNCIONALIDADES]

class MainActivity : AppCompatActivity() {

    // Funcionalidad: camara-tiempo-real
    private var imageCapture: ImageCapture? = null
    private val lanzadorPermisoCameraX = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) iniciarCameraX() else Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
    }
    // Funcionalidad: guardar-galeria
    private var imagenPendienteGaleria: Pair<Bitmap, String>? = null
    private val lanzadorPermisoGaleria = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) {
            imagenPendienteGaleria?.let { (bitmap, nombre) -> guardarImagenEnGaleria(bitmap, nombre) }
        } else {
            Toast.makeText(this, "Permiso de almacenamiento denegado", Toast.LENGTH_SHORT).show()
        }
        imagenPendienteGaleria = null
    }
    // [PROPIEDADES:FUNCIONALIDADES]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Funcionalidad: pantalla-encendida
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Funcionalidad: opencv
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "No se pudo inicializar OpenCV", Toast.LENGTH_SHORT).show()
        }
        // [ONCREATE:FUNCIONALIDADES]
        setContentView(R.layout.activity_main)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            iniciarCameraX()
        } else {
            lanzadorPermisoCameraX.launch(Manifest.permission.CAMERA)
        }
        // [ONCREATE_FIN:FUNCIONALIDADES]
        AppLogic.onIniciar(this)
    }

    private fun iniciarCameraX() {
        val proveedorFuturo = ProcessCameraProvider.getInstance(this)
        proveedorFuturo.addListener({
            val proveedorCamara = proveedorFuturo.get()
            val vistaPrevia = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.vistaPreviaCamara).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val analisisImagen = ImageAnalysis.Builder().build()
            try {
                proveedorCamara.unbindAll()
                proveedorCamara.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, vistaPrevia, imageCapture, analisisImagen
                )
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo iniciar la cámara: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tomarFotoCameraX(archivo: File, alTerminar: (Boolean) -> Unit) {
        val captura = imageCapture ?: return
        val opciones = ImageCapture.OutputFileOptions.Builder(archivo).build()
        captura.takePicture(opciones, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(salida: ImageCapture.OutputFileResults) = alTerminar(true)
            override fun onError(error: ImageCaptureException) = alTerminar(false)
        })
    }

    // Funcionalidad: vibracion
    private fun vibrar(duracionMs: Long = 200) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duracionMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duracionMs)
        }
    }

    // Funcionalidad: modo-inmersivo
    private fun activarModoInmersivo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun desactivarModoInmersivo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun pedirGuardarEnGaleria(bitmap: Bitmap, nombre: String = "imagen_${System.currentTimeMillis()}") {
        val necesitaPermiso = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (necesitaPermiso) {
            imagenPendienteGaleria = bitmap to nombre
            lanzadorPermisoGaleria.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            guardarImagenEnGaleria(bitmap, nombre)
        }
    }

    private fun guardarImagenEnGaleria(bitmap: Bitmap, nombre: String): Boolean {
        val valores = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores) ?: return false
        contentResolver.openOutputStream(uri)?.use { salida ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, salida)
        }
        return true
    }

    // [METODOS:FUNCIONALIDADES]
}
