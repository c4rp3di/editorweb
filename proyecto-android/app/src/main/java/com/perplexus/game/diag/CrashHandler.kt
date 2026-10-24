package com.perplexus.game.diag

import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Vuelca stack trace + últimas 200 líneas del log a filesDir/crash-YYYYMMDD-HHMMSS.txt. */
class CrashHandler private constructor(private val dir: File, private val previous: Thread.UncaughtExceptionHandler?) :
    Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val f = File(dir, "crash-$stamp.txt")
            PrintWriter(f).use { pw ->
                pw.println("Perplexus 3D crash — hilo '${t.name}'")
                pw.println("Fecha: ${Date()}")
                pw.println("Android SDK: ${android.os.Build.VERSION.SDK_INT}, dispositivo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                pw.println()
                e.printStackTrace(pw)
                pw.println()
                pw.println("---- Últimas 200 líneas del log ----")
                for (l in GameLog.lastLines(200)) pw.println(l)
            }
        } catch (_: Throwable) { }
        previous?.uncaughtException(t, e)
    }

    companion object {
        fun install(dir: File) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(dir, Thread.getDefaultUncaughtExceptionHandler()))
        }

        /** Crash más reciente (por fecha de modificación) o null. */
        fun latestCrash(dir: File): File? =
            dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() }
    }
}
