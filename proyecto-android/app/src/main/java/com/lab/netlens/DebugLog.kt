package com.lab.netlens

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.ArrayDeque

/** Local, bounded diagnostic log. Never records packet payloads, credentials or certificate keys. */
object DebugLog {
    private const val MAX_ENTRIES = 500
    private val lock = Any()
    private val entries = ArrayDeque<String>()
    @Volatile private var enabled = true

    fun log(area: String, message: String) {
        if (!enabled) return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        val line = "$ts [${Thread.currentThread().name}] [$area] ${message.replace('\n', ' ').replace('\r', ' ')}"
        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun snapshot(): String = synchronized(lock) {
        if (entries.isEmpty()) "NetLens: aún no hay eventos de diagnóstico." else entries.joinToString("\n")
    }

    fun clear() = synchronized(lock) { entries.clear() }

    fun count(): Int = synchronized(lock) { entries.size }
}
