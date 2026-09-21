package com.carpe.microlisto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.carpe.microlisto.audio.AudioRecorder
import com.carpe.microlisto.transcripcion.Transcriber
import com.carpe.microlisto.vad.SileroVad
import com.carpe.microlisto.vad.VadSegmenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class MicrolistoService : Service() {

    private var audioRecorder: AudioRecorder? = null
    private var transcriber: Transcriber? = null
    private var sileroVad: SileroVad? = null
    private var vadSegmenter: VadSegmenter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INICIAR -> iniciarGrabacion()
            ACTION_PARAR -> {
                detenerGrabacion()
                stopSelf()
            }
            else -> iniciarGrabacion()
        }
        return START_STICKY
    }

    private fun iniciarGrabacion() {
        val notificacion = construirNotificacion()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ID_NOTIFICACION,
                notificacion,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(ID_NOTIFICACION, notificacion)
        }

        _estado.value = EstadoGrabacion(
            grabando = true,
            tiempoMs = 0,
            transcripcionAcumulada = "",
            error = null
        )
        _textoAcumulado.value = ""
        _textoParcial.value = ""
        tiempoGrabadoMs = 0

        try {
            sileroVad = SileroVad(applicationContext).also { it.reset() }
            vadSegmenter = VadSegmenter()

            transcriber = Transcriber(
                context = applicationContext,
                onListo = {
                    // Listo para transcribir.
                },
                onParcial = { parcial ->
                    _textoParcial.value = limpiarJsonVosk(parcial)
                },
                onFinal = { final ->
                    val limpio = limpiarJsonVosk(final)
                    if (limpio.isNotBlank()) {
                        _textoAcumulado.value = _textoAcumulado.value + limpio + " "
                        _estado.value = _estado.value.copy(
                            transcripcionAcumulada = _textoAcumulado.value
                        )
                    }
                },
                onError = { error ->
                    _estado.value = _estado.value.copy(error = error)
                }
            )
            transcriber?.iniciar()

            val archivoWav = File(filesDir, "grabacion_${System.currentTimeMillis()}.wav")
            audioRecorder = AudioRecorder(
                archivoWav = archivoWav,
                onFrame = { frame, cantidad ->
                    val prob = try {
                        sileroVad?.calcularProbabilidad(frame) ?: 0f
                    } catch (_: Exception) {
                        0f
                    }
                    vadSegmenter?.actualizar(prob)

                    transcriber?.aceptarFrame(frame, cantidad)

                    tiempoGrabadoMs += (cantidad.toLong() * 1000L) / 16000L
                    _estado.value = _estado.value.copy(
                        tiempoMs = tiempoGrabadoMs,
                        transcripcionAcumulada = _textoAcumulado.value,
                        textoParcial = _textoParcial.value
                    )
                }
            )
            audioRecorder?.iniciar()
        } catch (e: Exception) {
            _estado.value = _estado.value.copy(
                grabando = false,
                error = e.message ?: "Error al iniciar la grabación"
            )
            detenerGrabacion()
            stopSelf()
        }
    }

    private fun detenerGrabacion() {
        try {
            audioRecorder?.parar()
        } catch (_: Exception) {}
        audioRecorder = null

        try {
            transcriber?.cerrar()
        } catch (_: Exception) {}
        transcriber = null

        try {
            sileroVad?.cerrar()
        } catch (_: Exception) {}
        sileroVad = null

        vadSegmenter?.reset()
        vadSegmenter = null

        _estado.value = _estado.value.copy(grabando = false)
        _textoParcial.value = ""
        tiempoGrabadoMs = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_ID,
                getString(R.string.notif_canal_grabacion),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_canal_grabacion_desc)
                setShowBadge(false)
            }
            val gestor = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            gestor.createNotificationChannel(canal)
        }
    }

    private fun construirNotificacion(): Notification {
        val intentAbrir = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingAbrir = PendingIntent.getActivity(
            this, 0, intentAbrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val intentParar = Intent(this, MicrolistoService::class.java).apply {
            action = ACTION_PARAR
        }
        val pendingParar = PendingIntent.getService(
            this, 1, intentParar,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CANAL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_grabando_titulo))
            .setContentText(getString(R.string.notif_grabando_texto))
            .setContentIntent(pendingAbrir)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notif_accion_parar),
                pendingParar
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun limpiarJsonVosk(json: String): String {
        val regex = Regex("\"(?:text|partial)\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(json)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    companion object {
        const val CANAL_ID = "microlisto_grabacion"
        const val ID_NOTIFICACION = 1001
        const val ACTION_INICIAR = "com.carpe.microlisto.INICIAR"
        const val ACTION_PARAR = "com.carpe.microlisto.PARAR"

        data class EstadoGrabacion(
            val grabando: Boolean = false,
            val tiempoMs: Long = 0,
            val transcripcionAcumulada: String = "",
            val textoParcial: String = "",
            val error: String? = null
        )

        private val _estado = MutableStateFlow(EstadoGrabacion())
        val estadoGrabacion: StateFlow<EstadoGrabacion> = _estado.asStateFlow()

        private val _textoAcumulado = MutableStateFlow("")
        private val _textoParcial = MutableStateFlow("")
        private var tiempoGrabadoMs: Long = 0

        fun iniciar(context: Context) {
            val intent = Intent(context, MicrolistoService::class.java).apply {
                action = ACTION_INICIAR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun parar(context: Context) {
            val intent = Intent(context, MicrolistoService::class.java).apply {
                action = ACTION_PARAR
            }
            context.startService(intent)
        }
    }
}