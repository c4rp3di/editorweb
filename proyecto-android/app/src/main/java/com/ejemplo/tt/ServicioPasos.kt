package com.ejemplo.tt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder

class ServicioPasos : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var sensorPasos: Sensor? = null

    private var contadorPasos = 0
    private var callbackCambio: ((Int) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): ServicioPasos = this@ServicioPasos
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorPasos = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        val prefs = getSharedPreferences("pasos_prefs", Context.MODE_PRIVATE)
        contadorPasos = prefs.getInt("pasos_totales", 0)

        crearCanalNotificacion()
        startForeground(1, crearNotificacion("Servicio activo - $contadorPasos pasos"))

        sensorPasos?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            contadorPasos += event.values[0].toInt()
            guardarPasos()
            callbackCambio?.invoke(contadorPasos)
            actualizarNotificacion()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun registrarCallback(callback: (Int) -> Unit) {
        this.callbackCambio = callback
        callback(contadorPasos)
    }

    fun obtenerPasos(): Int = contadorPasos

    fun reiniciarContador() {
        contadorPasos = 0
        guardarPasos()
        callbackCambio?.invoke(contadorPasos)
        actualizarNotificacion()
    }

    private fun guardarPasos() {
        val prefs = getSharedPreferences("pasos_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("pasos_totales", contadorPasos).apply()
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                "CANAL_PASOS",
                "Contador de Pasos",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(canal)
        }
    }

    private fun crearNotificacion(texto: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "CANAL_PASOS")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Contando Pasos")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun actualizarNotificacion() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, crearNotificacion("Pasos acumulados: $contadorPasos"))
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
}