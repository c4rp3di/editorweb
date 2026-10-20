package com.vigia.app.collect.inventory

import org.json.JSONArray
import org.json.JSONObject

/** Compara dos fotos del inventario y devuelve los cambios como pares (tipo, datos). */
object InventarioDiff {

    fun diferencias(anterior: JSONObject, nuevo: JSONObject): List<Pair<String, JSONObject>> {
        val res = ArrayList<Pair<String, JSONObject>>()
        val a = mapaApps(anterior)
        val b = mapaApps(nuevo)

        for ((paquete, n) in b) {
            val v = a[paquete]
            if (v == null) {
                res.add(
                    Pair(
                        "app_nueva",
                        JSONObject().put("paquete", paquete)
                            .put("sistema", n.optInt("s") == 1)
                            .put("instalador", n.optString("i"))
                    )
                )
                continue
            }
            if (v.optLong("c") != n.optLong("c")) {
                res.add(
                    Pair(
                        "app_actualizada",
                        JSONObject().put("paquete", paquete).put("de", v.optString("v")).put("a", n.optString("v"))
                    )
                )
            }
            val fa = v.optString("f")
            val fb = n.optString("f")
            if (fa.isNotEmpty() && fb.isNotEmpty() && fa != fb) {
                res.add(Pair("firma_cambiada", JSONObject().put("paquete", paquete)))
            }
            val permisosNuevos = nuevos(n.optJSONArray("g"), v.optJSONArray("g"))
            if (permisosNuevos.isNotEmpty()) {
                res.add(
                    Pair(
                        "permiso_concedido",
                        JSONObject().put("paquete", paquete).put("permisos", permisosNuevos.joinToString(","))
                    )
                )
            }
            val especialesNuevos = nuevos(n.optJSONArray("d"), v.optJSONArray("d"))
            if (especialesNuevos.isNotEmpty()) {
                res.add(
                    Pair(
                        "permiso_especial_declarado",
                        JSONObject().put("paquete", paquete).put("permisos", especialesNuevos.joinToString(","))
                    )
                )
            }
        }
        for ((paquete, _) in a) {
            if (!b.containsKey(paquete)) {
                res.add(Pair("app_eliminada", JSONObject().put("paquete", paquete)))
            }
        }

        listas(res, anterior, nuevo, "accesibilidad", "accesibilidad_activada", "accesibilidad_desactivada", true)
        listas(res, anterior, nuevo, "listeners", "lector_notificaciones_activado", "lector_notificaciones_desactivado", true)
        listas(res, anterior, nuevo, "teclados", "teclado_activado", "teclado_desactivado", true)
        listas(res, anterior, nuevo, "admins", "administrador_activado", "administrador_desactivado", true)
        listas(res, anterior, nuevo, "certs", "certificado_usuario_nuevo", "certificado_usuario_quitado", false)

        if (anterior.optInt("adb") != nuevo.optInt("adb")) {
            res.add(Pair("depuracion_usb_cambio", JSONObject().put("activa", nuevo.optInt("adb") == 1)))
        }
        return res
    }

    private fun listas(
        res: MutableList<Pair<String, JSONObject>>,
        anterior: JSONObject, nuevo: JSONObject,
        clave: String, tipoAlta: String, tipoBaja: String, esPaquete: Boolean
    ) {
        val antes = anterior.optJSONArray(clave)
        val ahora = nuevo.optJSONArray(clave)
        for (x in nuevos(ahora, antes)) {
            res.add(Pair(tipoAlta, datos(x, esPaquete)))
        }
        for (x in nuevos(antes, ahora)) {
            res.add(Pair(tipoBaja, datos(x, esPaquete)))
        }
    }

    private fun datos(valor: String, esPaquete: Boolean): JSONObject {
        return if (esPaquete) {
            JSONObject().put("paquete", valor.substringBefore('/')).put("componente", valor)
        } else {
            JSONObject().put("huella", valor)
        }
    }

    /** Elementos que están en [a] y no en [b]. */
    private fun nuevos(a: JSONArray?, b: JSONArray?): List<String> {
        val setB = HashSet<String>()
        if (b != null) {
            for (i in 0 until b.length()) setB.add(b.optString(i))
        }
        val res = ArrayList<String>()
        if (a != null) {
            for (i in 0 until a.length()) {
                val x = a.optString(i)
                if (!setB.contains(x)) res.add(x)
            }
        }
        return res
    }

    private fun mapaApps(o: JSONObject): Map<String, JSONObject> {
        val mapa = HashMap<String, JSONObject>()
        val apps = o.optJSONArray("apps") ?: return mapa
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            val paquete = app.optString("p")
            if (paquete.isNotEmpty()) mapa[paquete] = app
        }
        return mapa
    }
}
