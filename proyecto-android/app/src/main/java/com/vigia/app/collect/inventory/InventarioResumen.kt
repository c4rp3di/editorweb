package com.vigia.app.collect.inventory

import com.vigia.app.util.Formato
import org.json.JSONArray
import org.json.JSONObject

/** Convierte la última foto del inventario en un texto claro para la pantalla. */
object InventarioResumen {

    private val TIENDAS = setOf(
        "com.android.vending", "com.sec.android.app.samsungapps", "com.amazon.venezia",
        "com.huawei.appmarket", "com.xiaomi.mipicks", "com.xiaomi.market",
        "org.fdroid.fdroid", "org.fdroid.basic", "com.aurora.store"
    )

    private val GRUPOS = linkedMapOf(
        "READ_SMS" to "SMS", "RECEIVE_SMS" to "SMS", "SEND_SMS" to "SMS",
        "RECORD_AUDIO" to "Micrófono",
        "CAMERA" to "Cámara",
        "ACCESS_BACKGROUND_LOCATION" to "Ubicación en segundo plano",
        "READ_CALL_LOG" to "Registro de llamadas",
        "READ_CONTACTS" to "Contactos"
    )

    fun resumir(payload: String): String {
        val o = JSONObject(payload)
        val apps = o.optJSONArray("apps") ?: JSONArray()
        var total = 0
        var deUsuario = 0
        val fuera = ArrayList<String>()
        val porPermiso = LinkedHashMap<String, MutableList<String>>()
        for (etiqueta in GRUPOS.values) {
            if (!porPermiso.containsKey(etiqueta)) porPermiso[etiqueta] = ArrayList()
        }
        val superposicion = ArrayList<String>()
        val instaladores = ArrayList<String>()

        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            total++
            if (app.optInt("s") == 1) continue
            deUsuario++
            val paquete = app.optString("p")
            val instalador = app.optString("i")
            if (!TIENDAS.contains(instalador)) {
                fuera.add(if (instalador.isEmpty()) paquete else paquete + " (instalada por " + instalador + ")")
            }
            val concedidos = app.optJSONArray("g")
            if (concedidos != null) {
                for (j in 0 until concedidos.length()) {
                    val etiqueta = GRUPOS[concedidos.optString(j)]
                    if (etiqueta != null) {
                        val lista = porPermiso[etiqueta]
                        if (lista != null && !lista.contains(paquete)) lista.add(paquete)
                    }
                }
            }
            val declarados = app.optJSONArray("d")
            if (declarados != null) {
                for (j in 0 until declarados.length()) {
                    when (declarados.optString(j)) {
                        "SYSTEM_ALERT_WINDOW" -> superposicion.add(paquete)
                        "REQUEST_INSTALL_PACKAGES" -> instaladores.add(paquete)
                    }
                }
            }
        }

        val sb = StringBuilder()
        sb.append("Android: ").append(o.optString("android")).append('\n')
        sb.append("Último arranque del teléfono: ").append(Formato.fecha(o.optLong("arranque"))).append('\n')
        sb.append("Apps instaladas: ").append(total)
            .append(" (de usuario: ").append(deUsuario)
            .append(" · del sistema: ").append(total - deUsuario).append(")\n")
        sb.append("Depuración USB: ").append(if (o.optInt("adb") == 1) "ACTIVADA" else "apagada")
            .append(" · Opciones de desarrollador: ").append(if (o.optInt("dev") == 1) "activadas" else "apagadas")
            .append("\n\n")

        seccion(sb, "Servicios de accesibilidad activos", textos(o.optJSONArray("accesibilidad")), 20)
        seccion(sb, "Apps con acceso a leer notificaciones", textos(o.optJSONArray("listeners")), 20)
        seccion(sb, "Administradores del dispositivo", textos(o.optJSONArray("admins")), 20)
        seccion(sb, "Teclados activos", textos(o.optJSONArray("teclados")), 10)
        val certs = textos(o.optJSONArray("certs"))
        sb.append("Certificados instalados por el usuario: ").append(certs.size).append('\n')
        for (c in certs) sb.append("  • huella ").append(c).append('\n')
        sb.append('\n')

        seccion(sb, "Apps de usuario instaladas fuera de una tienda conocida", fuera, 40)
        for ((etiqueta, lista) in porPermiso) {
            seccion(sb, "Apps de usuario con permiso concedido de " + etiqueta, lista, 20)
        }
        seccion(sb, "Apps que declaran poder dibujar sobre otras", superposicion, 20)
        seccion(sb, "Apps que declaran poder instalar otras apps", instaladores, 20)

        sb.append("Esto es un inventario, no un diagnóstico: que una app tenga un permiso no significa que sea maliciosa. ")
        sb.append("Revisa con atención lo que no reconozcas.")
        return sb.toString()
    }

    private fun seccion(sb: StringBuilder, titulo: String, items: List<String>, maximo: Int) {
        sb.append(titulo).append(" (").append(items.size).append(")")
        if (items.isEmpty()) {
            sb.append(": ninguna\n\n")
            return
        }
        sb.append(":\n")
        for ((n, x) in items.withIndex()) {
            if (n >= maximo) {
                sb.append("  … y ").append(items.size - maximo).append(" más\n")
                break
            }
            sb.append("  • ").append(x).append('\n')
        }
        sb.append('\n')
    }

    private fun textos(a: JSONArray?): List<String> {
        val res = ArrayList<String>()
        if (a != null) {
            for (i in 0 until a.length()) res.add(a.optString(i))
        }
        return res
    }
}
