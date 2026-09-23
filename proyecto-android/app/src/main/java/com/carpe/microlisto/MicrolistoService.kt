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
import com.carpe.microlisto.data.AjustesDiarizacion
import com.carpe.microlisto.data.BaseDatos
import com.carpe.microlisto.data.Conversacion
import com.carpe.microlisto.data.Segmento
import com.carpe.microlisto.debug.DebugLog
import com.carpe.microlisto.reprocesado.AjustesReproceso
import com.carpe.microlisto.reprocesado.Reprocesador
import com.carpe.microlisto.transcripcion.Transcriber
import com.carpe.microlisto.transcripcion.VoskManager
import com.carpe.microlisto.vad.SileroVad
import com.carpe.microlisto.vad.VadSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MicrolistoService : Service() {

    private var audioRecorder: AudioRecorder? = null
    private var transcriber: Transcriber? = null
    private var sileroVad: SileroVad? = null
    private var vadSegmenter: VadSegmenter? = null
    private var archivoWavActual: File? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INICIAR -> iniciarGrabacion()
            ACTION_PARAR -> procesarYParar()
            else -> iniciarGrabacion()
        }
        return START_STICKY
    }

    private fun iniciarGrabacion() {
        val notificacion = construirNotificacion()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_NOTIFICACION, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID_NOTIFICACION, notificacion)
        }

        _estado.value = EstadoGrabacion(grabando = true)
        _textoAcumulado.value = ""
        _textoParcial.value = ""
        tiempoGrabadoMs = 0

        try {
            sileroVad = SileroVad(applicationContext).also { it.reset() }
            vadSegmenter = VadSegmenter()

            // Transcriber opcional: si no hay modelo Vosk, grabamos sin transcribir.
            val vm = VoskManager(applicationContext)
            if (vm.estaDescargado()) {
                transcriber = Transcriber(
                    context = applicationContext,
                    rutaModelo = vm.rutaModelo(),
                    onListo = { DebugLog.info("Servicio", "Transcriber listo") },
                    onParcial = { parcial -> _textoParcial.value = limpiarJsonVosk(parcial) },
                    onFinal = { final ->
                        val limpio = limpiarJsonVosk(final).trim()
                        if (limpio.isNotBlank()) {
                            val anterior = _textoAcumulado.value
                            val sep = if (anterior.isEmpty() || anterior.endsWith(" ")) "" else " "
                            _textoAcumulado.value = anterior + sep + limpio
                            _estado.value = _estado.value.copy(transcripcionAcumulada = _textoAcumulado.value)
                        }
                    },
                    onError = { error -> _estado.value = _estado.value.copy(error = error) }
                )
                transcriber?.iniciar()
            } else {
                DebugLog.warn("Servicio", "Modelo Vosk no descargado, se grabará sin transcripción")
                _estado.value = _estado.value.copy(
                    error = "Modelo Vosk no descargado. Se grabará sin transcripción."
                )
            }

            archivoWavActual = File(filesDir, "grabacion_${System.currentTimeMillis()}.wav")
            audioRecorder = AudioRecorder(
                archivoWav = archivoWavActual!!,
                onFrame = { frame, cantidad ->
                    val prob = try { sileroVad?.calcularProbabilidad(frame) ?: 0f } catch (_: Exception) { 0f }
                    vadSegmenter?.actualizar(prob)
                    if (transcriber?.estaListo() == true) {
                        transcriber?.aceptarFrame(frame, cantidad)
                    }
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
            _estado.value = _estado.value.copy(grabando = false, error = e.message ?: "Error al iniciar")
            detenerComponentes()
            stopSelf()
        }
    }

    private fun procesarYParar() {
        detenerComponentes()

        val wav = archivoWavActual
        val textoFinal = _textoAcumulado.value.trim()
        val duracionMs = tiempoGrabadoMs

        _estado.value = _estado.value.copy(grabando = false, procesando = true)

        if (wav == null || !wav.exists() || duracionMs < 1000) {
            _estado.value = _estado.value.copy(procesando = false)
            stopSelf()
            return
        }

        scope.launch {
            try {
                val db = BaseDatos(applicationContext)

                // Si no hay texto de Vosk, dejamos la transcripción vacía.
                // El usuario podrá reprocesar cuando tenga modelo descargado.
                val idConv = db.insertarConversacion(
                    Conversacion(
                        titulo = "Conversación ${formatearFecha(System.currentTimeMillis())}",
                        fechaMs = System.currentTimeMillis(),
                        duracionMs = duracionMs,
                        numHablantes = 0,
                        rutaAudio = wav.absolutePath,
                        transcripcion = textoFinal
                    )
                )

                val conv = db.obtenerConversacion(idConv)
                if (conv != null && textoFinal.isNotBlank()) {
                    // Solo diarizamos si hubo transcripción.
                    // La diarización tiene sentido una vez que hay texto que repartir.
                    Reprocesador.reprocesar(
                        context = applicationContext,
                        conversacion = conv,
                        ajustes = AjustesReproceso(
                            numHablantes = AjustesDiarizacion.getNumHablantes(applicationContext),
                            umbral = AjustesDiarizacion.getUmbral(applicationContext),
                            minFragMs = AjustesDiarizacion.getMinFragMs(applicationContext),
                            gapMs = AjustesDiarizacion.getGapMs(applicationContext),
                            hopMs = AjustesDiarizacion.getHopMs(applicationContext)
                        ),
                        db = db
                    )
                }

                AppLogic.exportarBackup(applicationContext)

                _estado.value = _estado.value.copy(procesando = false, idUltimaConversacion = idConv)
            } catch (e: Exception) {
                DebugLog.error("Servicio", "Error procesando: ${e.message}")
                _estado.value = _estado.value.copy(procesando = false, error = e.message)
            } finally {
                archivoWavActual = null
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun detenerComponentes() {
        try { audioRecorder?.parar() } catch (_: Exception) {}
        audioRecorder = null
        try { transcriber?.cerrar() } catch (_: Exception) {}
        transcriber = null
        try { sileroVad?.cerrar() } catch (_: Exception) {}
        sileroVad = null
        vadSegmenter?.reset()
        vadSegmenter = null
        _textoParcial.value = ""
    }

    private fun formatearFecha(ms: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_ID, getString(R.string.notif_canal_grabacion), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_canal_grabacion_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(canal)
        }
    }

    private fun construirNotificacion(): Notification {
        val intentAbrir = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingAbrir = PendingIntent.getActivity(this, 0, intentAbrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val intentParar = Intent(this, MicrolistoService::class.java).apply { action = ACTION_PARAR }
        val pendingParar = PendingIntent.getService(this, 1, intentParar,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CANAL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_grabando_titulo))
            .setContentText(getString(R.string.notif_grabando_texto))
            .setContentIntent(pendingAbrir)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_accion_parar), pendingParar)
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
            val procesando: Boolean = false,
            val tiempoMs: Long = 0,
            val transcripcionAcumulada: String = "",
            val textoParcial: String = "",
            val idUltimaConversacion: Long? = null,
            val error: String? = null
        )

        private val _estado = MutableStateFlow(EstadoGrabacion())
        val estadoGrabacion: StateFlow<EstadoGrabacion> = _estado.asStateFlow()

        private val _textoAcumulado = MutableStateFlow("")
        private val _textoParcial = MutableStateFlow("")
        private var tiempoGrabadoMs: Long = 0

        fun iniciar(context: Context) {
            val intent = Intent(context, MicrolistoService::class.java).apply { action = ACTION_INICIAR }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun parar(context: Context) {
            val intent = Intent(context, MicrolistoService::class.java).apply { action = ACTION_PARAR }
            context.startService(intent)
        }
    }
}