package com.vigia.app.collect.sensors

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.vigia.app.collect.EscritorEventos
import com.vigia.app.core.Recoleccion

/**
 * Módulo sensors: avisa de cuándo la CÁMARA o el MICRÓFONO pasan a estar en uso por alguna app
 * (y cuándo se liberan), con la hora, el tipo de uso y si la pantalla estaba encendida.
 *
 * Límite de Android: una app normal no puede saber QUÉ app usa el sensor. Si el módulo de
 * accesibilidad está activo se anota la app que estaba en pantalla; si no, queda «desconocida».
 * La ubicación no se puede observar desde fuera con permisos normales y no se registra.
 */
class ModuloSensores(private val ctx: Context, private val sink: EscritorEventos) {

    private val manejador = Handler(Looper.getMainLooper())
    private var callbackCamara: CameraManager.AvailabilityCallback? = null
    private var callbackMic: AudioManager.AudioRecordingCallback? = null
    private val camarasEnUso = HashMap<String, Boolean>()
    private var micActivo = false
    private var ultimasFuentes = ""

    fun iniciar() {
        try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val cb = object : CameraManager.AvailabilityCallback() {
                override fun onCameraUnavailable(cameraId: String) {
                    val antes = camarasEnUso[cameraId]
                    camarasEnUso[cameraId] = true
                    if (antes != true) registrar("camara", "en_uso · cámara " + cameraId)
                }

                override fun onCameraAvailable(cameraId: String) {
                    val antes = camarasEnUso[cameraId]
                    camarasEnUso[cameraId] = false
                    if (antes == true) registrar("camara", "libre · cámara " + cameraId)
                }
            }
            cm.registerAvailabilityCallback(cb, manejador)
            callbackCamara = cb
        } catch (e: Exception) {
        }

        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    alCambiarGrabacion(configs)
                }
            }
            am.registerAudioRecordingCallback(cb, manejador)
            callbackMic = cb
        } catch (e: Exception) {
        }
    }

    fun detener() {
        val c = callbackCamara
        if (c != null) {
            try {
                ctx.getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(c)
            } catch (e: Exception) {
            }
        }
        val m = callbackMic
        if (m != null) {
            try {
                ctx.getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(m)
            } catch (e: Exception) {
            }
        }
        callbackCamara = null
        callbackMic = null
    }

    private fun alCambiarGrabacion(configs: List<AudioRecordingConfiguration>?) {
        val lista = configs ?: emptyList()
        val activo = lista.isNotEmpty()
        val fuentes = lista.joinToString(", ") { c ->
            var texto = nombreFuente(c.clientAudioSource)
            if (Build.VERSION.SDK_INT >= 29 && c.isClientSilenced) texto += " (silenciada)"
            texto
        }
        if (activo != micActivo || (activo && fuentes != ultimasFuentes)) {
            registrar("microfono", if (activo) "en_uso · " + fuentes else "libre")
        }
        micActivo = activo
        ultimasFuentes = fuentes
    }

    private fun nombreFuente(fuente: Int): String {
        return when (fuente) {
            0 -> "predeterminada"
            1 -> "micrófono"
            5 -> "videocámara"
            6 -> "reconocimiento de voz"
            7 -> "llamada o VoIP"
            9 -> "sin procesar"
            10 -> "rendimiento de voz"
            else -> "fuente " + fuente
        }
    }

    private fun registrar(sensor: String, modo: String) {
        if (!Recoleccion.grabando) return
        val pm = ctx.getSystemService(PowerManager::class.java)
        val pantalla = pm.isInteractive
        val enPantalla = if (pantalla) Recoleccion.primerPlano else null
        val detalle = modo + " · pantalla " + (if (pantalla) "encendida" else "apagada")
        sink.sensor(uidDe(enPantalla), enPantalla ?: "desconocida", sensor, detalle)
    }

    private fun uidDe(paquete: String?): Int {
        if (paquete == null) return -1
        return try {
            ctx.packageManager.getApplicationInfo(paquete, 0).uid
        } catch (e: Exception) {
            -1
        }
    }
}
