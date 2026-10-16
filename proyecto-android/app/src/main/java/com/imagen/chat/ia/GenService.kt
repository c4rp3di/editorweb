package com.imagen.chat.ia

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlin.concurrent.thread

/** Mensajes entre la app (proceso principal) y GenService (proceso ":gen"). */
object GenProtocolo {
    const val MSG_INICIAR = 1
    const val MSG_PID = 2
    const val MSG_PROGRESO = 3
    const val MSG_FIN = 4

    fun imagen(
        modelPath: String,
        vaePath: String,
        prompt: String,
        negativePrompt: String,
        outputPath: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Long,
        cfg: Float,
        sharpness: Float,
        sampler: Int
    ): Bundle =
        Bundle().apply {
            putString("tipo", "imagen")
            putString("modelPath", modelPath)
            putString("vaePath", vaePath)
            putString("prompt", prompt)
            putString("negativePrompt", negativePrompt)
            putString("outputPath", outputPath)
            putInt("width", width)
            putInt("height", height)
            putInt("steps", steps)
            putLong("seed", seed)
            putFloat("cfg", cfg)
            putFloat("sharpness", sharpness)
            putInt("sampler", sampler)
        }
}

/**
 * Ejecuta stable-diffusion.cpp en un proceso propio (":gen").
 *  - "Detener" = matar este proceso: es la única forma fiable de parar una llamada nativa/GPU atascada.
 *  - Un ggml_abort, un "device lost" de Vulkan o un OOM solo matan este proceso, no la app.
 *  - Al terminar, el proceso se cierra solo: libera la RAM de los pesos y deja Vulkan limpio para la siguiente vez.
 */
class GenService : Service() {
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var cliente: Messenger? = null
    @Volatile private var ocupado = false

    private val entrante = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == GenProtocolo.MSG_INICIAR) iniciar(msg) else super.handleMessage(msg)
        }
    })

    override fun onCreate() {
        super.onCreate()
        DebugLog.initWorker(applicationContext)
        DebugLog.log("GEN-PROC", "proceso de generación iniciado · pid=${Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = entrante.binder

    private fun enviar(que: Int, relleno: Bundle.() -> Unit = {}) {
        val m = Message.obtain(null, que)
        m.data = Bundle().apply(relleno)
        try { cliente?.send(m) } catch (_: Exception) { }
    }

    private val sondeo = object : Runnable {
        override fun run() {
            if (!ocupado) return
            val p = DiffusionNative.progreso()
            enviar(GenProtocolo.MSG_PROGRESO) {
                putInt("fase", p.fase); putInt("paso", p.paso); putInt("pasos", p.pasos); putLong("ms", p.ultimoPasoMs)
            }
            principal.postDelayed(this, 500)
        }
    }

    private fun iniciar(msg: Message) {
        cliente = msg.replyTo
        if (ocupado) {
            enviar(GenProtocolo.MSG_FIN) { putBoolean("ok", false); putString("error", "Ya hay una generación en curso") }
            return
        }
        val datos = Bundle(msg.data)
        ocupado = true
        enviar(GenProtocolo.MSG_PID) { putInt("pid", Process.myPid()) }
        principal.post(sondeo)
        thread(name = "gen-nativo") {
            var ok = false
            var error = ""
            try {
                ok = DiffusionNative.generarImagen(
                    modelPath = datos.getString("modelPath") ?: "",
                    vaePath = datos.getString("vaePath").takeUnless { it.isNullOrBlank() },
                    prompt = datos.getString("prompt") ?: "",
                    negativePrompt = datos.getString("negativePrompt") ?: "",
                    outputPath = datos.getString("outputPath") ?: "",
                    width = datos.getInt("width"),
                    height = datos.getInt("height"),
                    steps = datos.getInt("steps"),
                    seed = datos.getLong("seed", -1L),
                    cfg = datos.getFloat("cfg", 7.0f),
                    sharpness = datos.getFloat("sharpness", 0.8f),
                    sampler = datos.getInt("sampler", 0)
                )
                if (!ok) error = "stable-diffusion.cpp no generó el resultado (mira [NATIVE] en el log)"
            } catch (t: Throwable) {
                error = "${t.javaClass.simpleName}: ${t.message}"
            }
            principal.post { terminar(ok, error) }
        }
    }

    private fun terminar(ok: Boolean, error: String) {
        ocupado = false
        DebugLog.log("GEN-PROC", "trabajo terminado · ok=$ok${if (error.isNotEmpty()) " · $error" else ""} · ${DebugLog.mem()}")
        enviar(GenProtocolo.MSG_FIN) { putBoolean("ok", ok); putString("error", error) }
        principal.postDelayed({ Process.killProcess(Process.myPid()) }, 600)
    }
}
