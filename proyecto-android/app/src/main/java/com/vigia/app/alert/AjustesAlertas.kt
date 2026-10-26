package com.vigia.app.alert

import android.content.Context
import com.vigia.app.store.KeyVault

/** Ajustes de alertas y de retención de datos. El token del servidor propio se guarda cifrado en el Keystore. */
class AjustesAlertas(contexto: Context) {
    private val ctx = contexto.applicationContext
    private val prefs = ctx.getSharedPreferences("vigia_alertas", Context.MODE_PRIVATE)

    var notificacionLocal: Boolean
        get() = prefs.getBoolean("local", true)
        set(valor) {
            prefs.edit().putBoolean("local", valor).apply()
        }

    /** Gravedad mínima para la alerta externa: media, alta o critica. */
    var umbral: String
        get() = prefs.getString("umbral", "alta") ?: "alta"
        set(valor) {
            prefs.edit().putString("umbral", valor).apply()
        }

    var httpsActivo: Boolean
        get() = prefs.getBoolean("https_activo", false)
        set(valor) {
            prefs.edit().putBoolean("https_activo", valor).apply()
        }

    var httpsUrl: String
        get() = prefs.getString("https_url", "") ?: ""
        set(valor) {
            prefs.edit().putString("https_url", valor).apply()
        }

    var smsActivo: Boolean
        get() = prefs.getBoolean("sms_activo", false)
        set(valor) {
            prefs.edit().putBoolean("sms_activo", valor).apply()
        }

    var smsNumero: String
        get() = prefs.getString("sms_numero", "") ?: ""
        set(valor) {
            prefs.edit().putString("sms_numero", valor).apply()
        }

    /** conservar, cerrar (borrar los datos crudos al cerrar la sesión) o siete_dias. */
    var retencion: String
        get() = prefs.getString("retencion", "conservar") ?: "conservar"
        set(valor) {
            prefs.edit().putString("retencion", valor).apply()
        }

    fun token(): String = KeyVault.leerSecreto(ctx, "token_https") ?: ""

    fun fijarToken(valor: String) {
        KeyVault.guardarSecreto(ctx, "token_https", valor)
    }

    fun pesoUmbral(): Int {
        return when (umbral) {
            "critica" -> 16
            "media" -> 4
            else -> 8
        }
    }
}
