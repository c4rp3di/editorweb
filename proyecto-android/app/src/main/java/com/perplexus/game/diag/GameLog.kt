package com.perplexus.game.diag

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/** Log circular en memoria (1000 líneas) con volcado asíncrono a filesDir/game.log. Thread-safe. */
object GameLog {
    enum class Level(val tag: String, val prio: Int) { DEBUG("D", Log.DEBUG), INFO("I", Log.INFO), WARN("W", Log.WARN), ERROR("E", Log.ERROR) }

    private const val CAPACITY = 1000
    private const val MAX_FILE_BYTES = 512 * 1024L
    private val ring = arrayOfNulls<String>(CAPACITY)
    private var head = 0
    private var count = 0
    private val lock = Any()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile var minLevel: Level = Level.DEBUG
    private var file: File? = null
    private var writer: FileWriter? = null
    private val executor = Executors.newSingleThreadExecutor(ThreadFactory { r ->
        Thread(r, "GameLogWriter").also { it.isDaemon = true }
    })

    fun init(logFile: File?) {
        synchronized(lock) {
            closeWriter()
            file = logFile
            try {
                if (logFile != null) {
                    if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
                        val old = File(logFile.parentFile, logFile.name + ".1")
                        old.delete(); logFile.renameTo(old)
                    }
                    writer = FileWriter(logFile, true)
                }
            } catch (e: Exception) {
                Log.e("GameLog", "No se pudo abrir el log", e)
                writer = null
            }
        }
        i("GameLog", "===== Inicio de sesión =====")
    }

    fun d(tag: String, msg: String, t: Throwable? = null) = log(Level.DEBUG, tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null) = log(Level.INFO, tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.ERROR, tag, msg, t)

    fun log(level: Level, tag: String, msg: String, t: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val text = if (t != null) msg + "\n" + Log.getStackTraceString(t) else msg
        try { Log.println(level.prio, tag, text) } catch (_: Throwable) { }
        val line: String = synchronized(lock) {
            val l = "${fmt.format(Date())} ${level.tag}/$tag: $text"
            ring[head] = l
            head = (head + 1) % CAPACITY
            if (count < CAPACITY) count++
            l
        }
        val w = writer
        if (w != null) {
            try {
                executor.execute {
                    try { w.write(line); w.write("\n"); w.flush() } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }

    /** Últimas n líneas (la más antigua primero). */
    fun lastLines(n: Int): List<String> {
        synchronized(lock) {
            val m = if (n < count) n else count
            val out = ArrayList<String>(m)
            for (k in m downTo 1) {
                val idx = ((head - k) % CAPACITY + CAPACITY) % CAPACITY
                ring[idx]?.let { out.add(it) }
            }
            return out
        }
    }

    fun clear() { synchronized(lock) { head = 0; count = 0; for (i in ring.indices) ring[i] = null } }

    private fun closeWriter() { try { writer?.close() } catch (_: Exception) { }; writer = null }

    fun close() {
        i("GameLog", "===== Fin de sesión =====")
        executor.execute { }
        synchronized(lock) { closeWriter() }
    }
}
