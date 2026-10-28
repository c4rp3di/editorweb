package com.lab.netlens

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object SessionHistory {
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private var started = 0L
    private var target = "Todas"
    @Synchronized fun init(c: Context) { file = File(c.applicationContext.filesDir, "sessions.json") }
    @Synchronized fun begin(pkg: String?) { started = System.currentTimeMillis(); target = pkg ?: "Todas" }
    @Synchronized fun finish() {
        if (started == 0L) return
        val entry = JSONObject().put("start", started).put("end", System.currentTimeMillis()).put("target", target)
            .put("apps", Store.apps.size).put("connectionsRetained", Store.snapshot().size)
            .put("up", Store.apps.values.sumOf { it.up.get() }).put("down", Store.apps.values.sumOf { it.down.get() })
        started = 0L
        io.execute { synchronized(this) {
            try {
                val old = if (file.exists()) JSONArray(file.readText()) else JSONArray()
                val out = JSONArray()
                for (i in maxOf(0, old.length() - 49) until old.length()) out.put(old.getJSONObject(i))
                out.put(entry)
                val temp = File(file.parentFile, "sessions.tmp"); temp.writeText(out.toString())
                if (!temp.renameTo(file)) { file.writeText(out.toString()); temp.delete() }
            } catch (_: Exception) { }
        } }
    }
    @Synchronized fun recent(): String = try {
        if (!file.exists()) "Sin sesiones guardadas. Se guardan al detener captura; un cierre forzado puede perder la sesión activa."
        else {
            val rows = JSONArray(file.readText())
            (rows.length() - 1 downTo maxOf(0, rows.length() - 15)).joinToString("\n\n") { i ->
                val o = rows.getJSONObject(i)
                "${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(o.optLong("start")))} · ${o.optString("target")}\n" +
                    "${o.optInt("apps")} apps · ↑${o.optLong("up")} B ↓${o.optLong("down")} B"
            }.ifEmpty { "Sin sesiones." }
        }
    } catch (_: Exception) { "Historial no legible." }
    fun clear() { io.execute { synchronized(this) { if (file.exists()) file.delete() } } }
}
