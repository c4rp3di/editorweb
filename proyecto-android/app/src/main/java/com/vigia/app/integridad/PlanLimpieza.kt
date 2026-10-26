package com.vigia.app.integridad

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan de limpieza: lista de apps (normalmente del sistema) que el usuario quiere desactivar o quitar para su usuario.
 * Una app normal no puede hacerlo sola (hace falta privilegio de ADB), así que Vigía genera los comandos listos para
 * ejecutar, con la parte de «deshacer», y se niega a incluir apps esenciales que podrían dejar el teléfono inutilizable.
 */
object PlanLimpieza {
    const val DESACTIVAR = "desactivar"
    const val DESINSTALAR = "desinstalar"
    private const val PREFS = "vigia_plan"
    private const val CLAVE = "entradas"

    class Entrada(val paquete: String, val etiqueta: String, val accion: String)

    private val ESENCIALES = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.server.telecom",
        "com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.google.android.gms",
        "com.google.android.gsf", "com.android.vending", "com.android.nfc", "com.android.bluetooth",
        "com.android.keychain", "com.android.location.fused", "com.android.networkstack", "com.android.networkstack.inprocess",
        "com.android.captiveportallogin", "com.android.cellbroadcastreceiver", "com.android.emergency",
        "com.google.android.webview", "com.android.webview", "com.android.certinstaller", "com.android.se",
        "com.android.stk", "com.android.mms.service", "com.google.android.networkstack", "com.vigia.app"
    )

    fun leer(contexto: Context): List<Entrada> {
        val texto = contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLAVE, "[]") ?: "[]"
        val res = ArrayList<Entrada>()
        try {
            val a = JSONArray(texto)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                res.add(Entrada(o.optString("p"), o.optString("e"), o.optString("a")))
            }
        } catch (e: Exception) {
        }
        return res
    }

    private fun guardar(contexto: Context, lista: List<Entrada>) {
        val a = JSONArray()
        for (e in lista) a.put(JSONObject().put("p", e.paquete).put("e", e.etiqueta).put("a", e.accion))
        contexto.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CLAVE, a.toString()).apply()
    }

    fun anadir(contexto: Context, paquete: String, etiqueta: String, accion: String) {
        val lista = leer(contexto).filter { it.paquete != paquete }.toMutableList()
        lista.add(Entrada(paquete, etiqueta, accion))
        guardar(contexto, lista)
    }

    fun quitar(contexto: Context, paquete: String) {
        guardar(contexto, leer(contexto).filter { it.paquete != paquete })
    }

    fun vaciar(contexto: Context) {
        guardar(contexto, emptyList())
    }

    /** Devuelve por qué una app no se debe tocar, o null si se puede incluir en el plan. */
    fun motivoCritica(contexto: Context, paquete: String): String? {
        if (ESENCIALES.contains(paquete)) return "es un componente esencial del sistema"
        if (paquete.startsWith("com.android.providers.") || paquete.startsWith("com.android.internal.")) {
            return "es un proveedor de datos del sistema que otras apps necesitan"
        }
        try {
            val inicio = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val lanzador = contexto.packageManager.resolveActivity(inicio, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            if (lanzador == paquete) return "es tu pantalla de inicio actual"
        } catch (e: Exception) {
        }
        try {
            val teclado = Settings.Secure.getString(contexto.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
            if (teclado == paquete) return "es tu teclado actual"
        } catch (e: Exception) {
        }
        try {
            val tm = contexto.getSystemService(TelecomManager::class.java)
            if (tm.defaultDialerPackage == paquete) return "es tu app de teléfono"
        } catch (e: Exception) {
        }
        try {
            val sms = android.provider.Telephony.Sms.getDefaultSmsPackage(contexto)
            if (sms == paquete) return "es tu app de mensajes"
        } catch (e: Exception) {
        }
        return null
    }

    /** Texto con los comandos de ADB y, comentada, la parte de deshacer. */
    fun guion(lista: List<Entrada>, fecha: String): String {
        val sb = StringBuilder()
        sb.append("# Plan de limpieza de Vigía · ").append(fecha).append('\n')
        sb.append("# Cómo usarlo: conecta el teléfono con ADB (por USB o con la depuración inalámbrica) y ejecuta estas líneas una a una.\n")
        sb.append("# Revisa cada línea antes de ejecutarla. Vigía no incluye apps esenciales, pero tú conoces tu teléfono mejor que nadie.\n")
        sb.append("# Las apps desactivadas o quitadas para el usuario 0 se pueden recuperar con la sección «Deshacer» del final.\n\n")
        for (e in lista) {
            if (e.accion == DESACTIVAR) {
                sb.append("# ").append(e.etiqueta).append(" · desactivar\n")
                sb.append("adb shell pm disable-user --user 0 ").append(e.paquete).append("\n\n")
            } else {
                sb.append("# ").append(e.etiqueta).append(" · quitar para este usuario\n")
                sb.append("adb shell pm uninstall --user 0 ").append(e.paquete).append("\n\n")
            }
        }
        sb.append("# ---------- Deshacer (quita el # de las líneas que necesites) ----------\n")
        for (e in lista) {
            if (e.accion == DESACTIVAR) {
                sb.append("# adb shell pm enable ").append(e.paquete).append('\n')
            } else {
                sb.append("# adb shell cmd package install-existing ").append(e.paquete).append('\n')
            }
        }
        return sb.toString()
    }
}
