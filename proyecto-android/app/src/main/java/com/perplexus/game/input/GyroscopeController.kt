package com.perplexus.game.input

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.perplexus.game.diag.GameLog
import kotlin.math.sqrt

/** Registra el giroscopio, detecta saturación del rango y vuelca muestras al buffer. */
class GyroscopeController(private val sm: SensorManager, @JvmField val ring: SampleRing) : SensorEventListener {
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    @Volatile var registered = false
        private set
    @Volatile var saturated = false
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
        GameLog.i(TAG, "Giroscopio registrado=$registered (${s.name}, rango=${s.maximumRange} rad/s)")
        return registered
    }

    fun stop() {
        if (!registered) return
        sm.unregisterListener(this)
        registered = false
        GameLog.i(TAG, "Giroscopio dado de baja")
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        val range = maxRange
        saturated = range > 0f && mag >= range * 0.98f
        ring.push(e.timestamp, v[0], v[1], v[2])
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) { accuracy = a }

    companion object {
        private const val TAG = "Gyro"
        const val SAMPLING_US = 5000
    }
}
