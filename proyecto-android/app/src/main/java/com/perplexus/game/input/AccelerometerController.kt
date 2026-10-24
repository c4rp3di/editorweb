package com.perplexus.game.input

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.perplexus.game.diag.GameLog

/** Registra el acelerómetro y vuelca las muestras en un buffer lock-free (hilo de sensores). */
class AccelerometerController(private val sm: SensorManager, @JvmField val ring: SampleRing) : SensorEventListener {
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    @Volatile var registered = false
        private set
    @Volatile var accuracy: Int = -1
        private set

    val available: Boolean get() = sensor != null
    val maxRange: Float get() = sensor?.maximumRange ?: 0f

    fun start(handler: Handler): Boolean {
        val s = sensor ?: return false
        if (registered) return true
        registered = try {
            sm.registerListener(this, s, SAMPLING_US, handler)
        } catch (e: SecurityException) {
            GameLog.w(TAG, "Sin permiso para muestreo rápido; uso SENSOR_DELAY_GAME", e)
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, handler)
        }
        GameLog.i(TAG, "Acelerómetro registrado=$registered (${s.name}, minDelay=${s.minDelay}µs)")
        return registered
    }

    fun stop() {
        if (!registered) return
        sm.unregisterListener(this)
        registered = false
        GameLog.i(TAG, "Acelerómetro dado de baja")
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        ring.push(e.timestamp, v[0], v[1], v[2])
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) { accuracy = a }

    companion object {
        private const val TAG = "Accel"
        const val SAMPLING_US = 5000
    }
}
