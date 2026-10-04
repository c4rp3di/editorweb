package com.carpe.espejoslocos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {

    private var grabacionPendiente = false

    private val lanzadorPermisoCamara =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) {
                AppLogic.onIniciar(this)
            } else {
                Toast.makeText(
                    this,
                    "Permiso de cámara denegado",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val lanzadorPermisoAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            // El vídeo puede grabarse sin audio si el usuario no concede el micrófono.
            if (grabacionPendiente) {
                grabacionPendiente = false
                iniciarGrabacionVideo(concedido)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(
                this,
                "No se pudo inicializar OpenCV",
                Toast.LENGTH_LONG
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
            lanzadorPermisoCamara.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Llamado por el botón de grabación del OverlayUI.
     */
    fun alternarGrabacionVideo() {

        // Si ya está grabando, detenemos.
        if (MotorCamara.estaGrabando()) {
            MotorCamara.detenerGrabacion()
            return
        }

        val tieneAudio =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        if (tieneAudio) {
            iniciarGrabacionVideo(true)
        } else {
            // Pedimos micrófono una sola vez.
            // Si se rechaza, grabamos igualmente sin audio.
            grabacionPendiente = true

            lanzadorPermisoAudio.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun iniciarGrabacionVideo(conAudio: Boolean) {

        MotorCamara.iniciarGrabacion(
            activity = this,
            conAudio = conAudio,
            onCambioEstado = { grabando ->

                // El callback llega al hilo principal desde CameraX.
                if (!grabando) {
                    grabacionPendiente = false
                }
            }
        )
    }

    override fun onDestroy() {
        MotorCamara.detener()
        super.onDestroy()
    }
}