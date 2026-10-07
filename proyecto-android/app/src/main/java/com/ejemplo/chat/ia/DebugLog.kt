package com.ejemplo.chat.ia

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de depuración persistente. Cada línea se escribe y se sincroniza a disco al momento,
 * así que sobrevive a un cierre brusco de la app (OOM / kill del sistema) y se puede leer al reabrirla.
 */
object DebugLog {
    private const val MAX_BYTES = 300_000L
    private var appContext: Context? = null
    private var file: File? = null
    private var marker: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fmtFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** true si la app se cerró en mitad de una generación de imagen en la sesión anterior. */
    @Volatile var interruptedLast = false
        private set

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        file = File(context.filesDir, "debug.log")
        marker = File(context.filesDir, "gen_in_progress")
        val f = file!!
        try {
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.takeLast((MAX_BYTES / 2).toInt()))
            }
        } catch (_: Exception) {}
        log("APP", "=== inicio · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()} ===")
        marker?.let {
            if (it.exists()) {
                interruptedLast = true
                log("APP", "⚠ La generación anterior NO terminó (${it.readText().take(80)}). La app se cerró durante ella.")
                it.delete()
            }
        }
        logPreviousExits(context)
        absorberRegistroNativo()
        log("MEM", mem())
    }

    /** Inicialización mínima para el proceso ":gen": solo habilita escribir en debug.log (sin absorber ni marcar nada). */
    @Synchronized
    fun initWorker(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        file = File(context.filesDir, "debug.log")
    }

    /** Vuelca al log principal las últimas líneas de native.log sin borrarlo (se usa cuando el proceso de generación muere). */
    fun volcarNativoReciente(maxLineas: Int = 60) {
        val f = appContext?.let { File(it.filesDir, "native.log") } ?: return
        try {
            if (!f.isFile || f.length() == 0L) return
            val lineas = f.readText().takeLast(12_000).lines().filter { it.isNotBlank() }.takeLast(maxLineas)
            log("NATIVE", "--- salida nativa reciente (${lineas.size} líneas) ---")
            lineas.forEach { log("NATIVE", it.take(300)) }
            log("NATIVE", "--- fin ---")
        } catch (e: Exception) {
            log("NATIVE", "no se pudo leer native.log: ${e.message}")
        }
    }

    /** Archivo donde el código nativo vuelca stdout/stderr y los informes de señal (crash). */
    fun nativeLogPath(): String? = appContext?.let { File(it.filesDir, "native.log").absolutePath }

    /** Pasa al log principal lo que dejó el código nativo en la sesión anterior (incluye el motivo de un crash). */
    private fun absorberRegistroNativo() {
        val f = appContext?.let { File(it.filesDir, "native.log") } ?: return
        try {
            if (!f.isFile || f.length() == 0L) return
            val texto = f.readText().takeLast(14_000)
            val lineas = texto.lines().filter { it.isNotBlank() }.takeLast(120)
            log("NATIVE", "--- salida nativa de la sesión anterior (${lineas.size} líneas, ${f.length() / 1024} KB en total) ---")
            lineas.forEach { log("NATIVE", it.take(300)) }
            log("NATIVE", "--- fin salida nativa ---")
            f.delete()
        } catch (e: Exception) {
            log("NATIVE", "no se pudo leer native.log: ${e.message}")
        }
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        val f = file ?: return
        try {
            FileOutputStream(f, true).use { out ->
                out.write("${fmt.format(Date())} [$tag] $msg\n".toByteArray())
                out.fd.sync()
            }
        } catch (_: Exception) {}
    }

    fun markStart(prompt: String) {
        try { marker?.writeText("${fmtFull.format(Date())} · ${prompt.take(60)}") } catch (_: Exception) {}
    }

    fun markEnd() {
        try { marker?.delete() } catch (_: Exception) {}
    }

    /** Memoria del proceso: RSS, heap nativo, heap Java y RAM libre del sistema. */
    fun mem(): String {
        val mb = 1024L * 1024L
        val rss = try {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS") }?.filter { it.isDigit() }?.toLongOrNull()?.div(1024)
            }
        } catch (_: Exception) { null }
        val rt = Runtime.getRuntime()
        val java = (rt.totalMemory() - rt.freeMemory()) / mb
        val nativo = Debug.getNativeHeapAllocatedSize() / mb
        val info = ActivityManager.MemoryInfo()
        (appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
        val libre = info.availMem / mb
        return "RSS ${rss ?: "?"} MB · nativo $nativo MB · java $java MB · libre sistema $libre MB${if (info.lowMemory) " · ⚠ POCA MEMORIA" else ""}"
    }

    fun resumen(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n${mem()}"

    fun read(maxChars: Int = 40_000): String =
        try { file?.takeIf { it.isFile }?.readText()?.takeLast(maxChars) ?: "" } catch (_: Exception) { "" }

    @Synchronized
    fun clear() {
        try { file?.writeText("") } catch (_: Exception) {}
        log("APP", "registro borrado")
    }

    private fun logPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val prefs = context.getSharedPreferences("debug", Context.MODE_PRIVATE)
            val last = prefs.getLong("exit_ts", 0L)
            var newest = last
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .sortedBy { it.timestamp }
                .filter { it.timestamp > last }
                .forEach {
                    newest = maxOf(newest, it.timestamp)
                    log(
                        "EXIT",
                        "${fmtFull.format(Date(it.timestamp))} motivo=${reasonName(it.reason)} (${it.reason}) " +
                            "rss=${it.rss / 1024} MB pss=${it.pss / 1024} MB importancia=${it.importance} desc=${it.description}"
                    )
                }
            prefs.edit().putLong("exit_ts", newest).apply()
        } catch (e: Exception) {
            log("EXIT", "no se pudo leer el motivo del cierre anterior: ${e.message}")
        }
    }

    private fun reasonName(r: Int) = when (r) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED (kill por señal)"
        3 -> "LOW_MEMORY (sin memoria)"
        4 -> "CRASH (excepción Java)"
        5 -> "CRASH_NATIVE (fallo nativo)"
        6 -> "ANR (no responde)"
        7 -> "INITIALIZATION_FAILURE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        13 -> "OTHER"
        14 -> "FREEZER"
        else -> "UNKNOWN"
    }
}
