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

object DebugLog {
    private const val MAX_BYTES = 300_000L
    private var appContext: Context? = null
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        file = File(context.filesDir, "debug.log")
        val f = file!!
        try {
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.takeLast((MAX_BYTES / 2).toInt()))
            }
        } catch (_: Exception) {}
        log("APP", "=== inicio · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} ===")
    }

    @Synchronized
    fun initWorker(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        file = File(context.filesDir, "debug.log")
    }

    fun nativeLogPath(): String? = appContext?.let { File(it.filesDir, "native.log").absolutePath }

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
        return "RSS ${rss ?: "?"} MB · nativo $nativo MB · java $java MB · libre $libre MB"
    }

    fun read(maxChars: Int = 40_000): String =
        try { file?.takeIf { it.isFile }?.readText()?.takeLast(maxChars) ?: "" } catch (_: Exception) { "" }

    @Synchronized
    fun clear() {
        try { file?.writeText("") } catch (_: Exception) {}
        log("APP", "registro borrado")
    }
}
