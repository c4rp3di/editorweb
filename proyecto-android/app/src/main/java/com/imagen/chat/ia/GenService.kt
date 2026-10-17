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
        sampler: Int,
        mantener: Boolean = false,
        flashAttn: Boolean = true,
        vaeTile: Int = 256,
        vaeGpu: Boolean = false,
        difusionCpu: Boolean = false
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
            putBoolean("mantener", mantener)
            putBoolean("flash", flashAttn)
            putInt("tile", vaeTile)
            putBoolean("vaeGpu", vaeGpu)
            putBoolean("difCpu", difusionCpu)
        }
}

/**
 * Ejecuta stable-diffusion.cpp en un proceso propio (":gen").
 *  - "Detener" = matar este proceso: es la única forma fiable de parar una llamada nativa/GPU atascada.
 *  - Un ggml_abort, un "device lost" de Vulkan o un OOM solo matan este proceso, no la app.
 *  - Al terminar, el proceso se cierra solo: libera la RAM de los pesos y deja Vulkan limpio para la siguiente vez.
 *  - Excepción (opcional): con "mantener modelo" el proceso espera IDLE_MS antes de cerrarse y el motor
 *    nativo conserva el modelo cargado; si llega otro trabajo a tiempo se reutiliza sin releer los pesos.
 */
class GenService : Service() {
    companion object {
        private const val IDLE_MS = 120_000L
        private const val CIERRE_NORMAL_MS = 600L
        // Estático a propósito: el servicio se destruye al desvincularse, pero el proceso (y este cierre pendiente) sigue vivo.
        private val hiloPrincipal = Handler(Looper.getMainLooper())
        private val cierre = Runnable { Process.killProcess(Process.myPid()) }
    }

    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var cliente: Messenger? = null
    @Volatile private var ocupado = false
    @Volatile private var mantener = false

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
        hiloPrincipal.removeCallbacks(cierre)   // cancela un cierre pendiente del trabajo anterior
        mantener = datos.getBoolean("mantener", false)
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
                    sampler = datos.getInt("sampler", 0),
                    mantenerCargado = mantener,
                    flashAttn = datos.getBoolean("flash", true),
                    vaeTile = datos.getInt("tile", 256),
                    vaeGpu = datos.getBoolean("vaeGpu", false),
                    difusionCpu = datos.getBoolean("difCpu", false)
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
        hiloPrincipal.removeCallbacks(cierre)
        hiloPrincipal.postDelayed(cierre, if (ok && mantener) IDLE_MS else CIERRE_NORMAL_MS)
    }
}
