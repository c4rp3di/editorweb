package com.luzcontrol.app.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Datadis no permite repetir la misma consulta en 24 h (ni las que hayan devuelto error).
 * Este registro persistente recuerda cuándo se hizo cada consulta (clave = endpoint + parámetros)
 * para que la app NUNCA la repita antes de tiempo, aunque se reinicie o falle y reintente un worker.
 * También frena los reintentos de login con credenciales ya rechazadas y tras un bloqueo.
 */
@Singleton
class ApiCallGuard @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("datadis_call_guard", Context.MODE_PRIVATE)

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    fun remainingMs(key: String, windowMs: Long = DAY_MS, now: Long = System.currentTimeMillis()): Long {
        val last = prefs.getLong(hash(key), 0L)
        return (last + windowMs - now).coerceAtLeast(0L)
    }

    fun mark(key: String, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(hash(key), now).apply()
        prune(now)
    }

    fun clear(key: String) {
        prefs.edit().remove(hash(key)).apply()
    }

    private fun prune(now: Long) {
        val editor = prefs.edit()
        var changed = false
        for ((k, v) in prefs.all) {
            if (v is Long && now - v > DAY_MS + 60_000L) { editor.remove(k); changed = true }
        }
        if (changed) editor.apply()
    }

    companion object {
        const val DAY_MS = 24L * 3600_000L + 5L * 60_000L // 24 h + margen
        const val LOGIN_REJECTED_MS = 3600_000L
        const val BLOCKED_MS = 6L * 3600_000L
    }
}
