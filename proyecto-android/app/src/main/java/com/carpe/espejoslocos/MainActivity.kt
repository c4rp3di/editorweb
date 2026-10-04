package com.carpe.espejoslocos

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.carpe.espejoslocos.AppLogic
import org.opencv.android.OpenCVLoader
import java.io.File

class MainActivity : AppCompatActivity() {

    private var imagenPendienteGaleria: Pair<Bitmap, String>? = null

    private val lanzadorPermisoCameraX =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) {
                AppLogic.onIniciar(this)
            } else {
                Toast.makeText(
                    this,
                    "Permiso de cámara denegado",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val lanzadorPermisoGaleria =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) {
                imagenPendienteGaleria?.let { (bitmap, nombre) ->
                    guardarImagenEnGaleria(bitmap, nombre)
                }
            } else {
                Toast.makeText(
                    this,
                    "Permiso de almacenamiento denegado",
                    Toast.LENGTH_SHORT
                ).show()
            }
            imagenPendienteGaleria = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(
                this,
                "No se pudo inicializar OpenCV",
                Toast.LENGTH_SHORT
            ).show()
        }

        setContentView(R.layout.activity_main)

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            AppLogic.onIniciar(this)
        } else {
            lanzadorPermisoCameraX.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    override fun onDestroy() {
        MotorCamara.detener()
        super.onDestroy()
    }

    fun vibrar(duracionMs: Long = 200) {
        val vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager =
                    getSystemService(
                        Context.VIBRATOR_MANAGER_SERVICE
                    ) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(
                    Context.VIBRATOR_SERVICE
                ) as Vibrator
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    duracionMs,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duracionMs)
        }
    }

    fun activarModoInmersivo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    fun desactivarModoInmersivo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                WindowInsets.Type.systemBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun pedirGuardarEnGaleria(
        bitmap: Bitmap,
        nombre: String =
            "imagen_${System.currentTimeMillis()}"
    ) {
        val necesitaPermiso =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED

        if (necesitaPermiso) {
            imagenPendienteGaleria = bitmap to nombre
            lanzadorPermisoGaleria.launch(
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            guardarImagenEnGaleria(
                bitmap,
                nombre
            )
        }
    }

    private fun guardarImagenEnGaleria(
        bitmap: Bitmap,
        nombre: String
    ): Boolean {

        val valores =
            ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    nombre
                )
                put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/EspejosLocos"
                    )
                }
            }

        val uri =
            contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                valores
            ) ?: return false

        return try {
            contentResolver.openOutputStream(uri)?.use { salida ->
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    95,
                    salida
                )
            }
            true
        } catch (e: Exception) {
            LogEspejos.e(
                "Error guardando imagen",
                e
            )
            false
        }
    }
}
