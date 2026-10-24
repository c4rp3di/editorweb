package com.perplexus.game.lifecycle

import android.content.Context
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.perplexus.game.diag.GameLog
import com.perplexus.game.input.AccelerometerController
import com.perplexus.game.input.GyroscopeController
import com.perplexus.game.input.SampleRing

/** Gestiona el ciclo de vida de los sensores y su hilo propio (plan §5.3/§5.4). */
class GameLifecycle(context: Context) {
    @JvmField val accelRing = SampleRing(1024)
    @JvmField val gyroRing = SampleRing(1024)
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    @JvmField val accel = AccelerometerController(sm, accelRing)
    @JvmField val gyro = GyroscopeController(sm, gyroRing)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    val sensorsAvailable: Boolean get() = accel.available && gyro.available
    val missingSensorMessage: String
        get() = when {
            !gyro.available && !accel.available -> "Este dispositivo no tiene giroscopio ni acelerómetro."
            !gyro.available -> "Este dispositivo no tiene giroscopio."
            !accel.available -> "Este dispositivo no tiene acelerómetro."
            else -> ""
        }

    fun start() {
        if (thread == null) {
            val t = HandlerThread("SensorThread")
            t.start()
            thread = t
            handler = Handler(t.looper)
        }
        val h = handler ?: return
        accel.start(h)
        gyro.start(h)
    }

    fun stop() {
        accel.stop(); gyro.stop()
    }

    fun destroy() {
        stop()
        thread?.quitSafely()
        thread = null; handler = null
        GameLog.i("Lifecycle", "Hilo de sensores detenido (acel descartadas=${accelRing.dropped}, giro descartadas=${gyroRing.dropped})")
    }
}
