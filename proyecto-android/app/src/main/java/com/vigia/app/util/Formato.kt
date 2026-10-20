package com.vigia.app.util

import android.text.format.DateFormat
import org.json.JSONObject
import java.util.Date

object Formato {
    fun duracion(ms: Long): String {
        val s = if (ms < 0) 0L else ms / 1000
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    fun fecha(ms: Long): String {
        return DateFormat.format("d MMM yyyy HH:mm", Date(ms)).toString()
    }

    fun hora(ms: Long): String {
        return DateFormat.format("HH:mm:ss", Date(ms)).toString()
    }

    /** Convierte un JSON de evento en «clave=valor · clave=valor». Si no es JSON, lo devuelve tal cual. */
    fun payload(json: String): String {
        return try {
            val o = JSONObject(json)
            val partes = ArrayList<String>()
            val claves = o.keys()
            while (claves.hasNext()) {
                val k = claves.next()
                partes.add(k + "=" + o.opt(k))
            }
            if (partes.isEmpty()) "" else partes.joinToString(" · ")
        } catch (e: Exception) {
            json
        }
    }
}
