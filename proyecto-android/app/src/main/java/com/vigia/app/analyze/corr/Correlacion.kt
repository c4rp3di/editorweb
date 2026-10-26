package com.vigia.app.analyze.corr

import com.vigia.app.analyze.AjustesAnalisis
import com.vigia.app.analyze.DatosSesion
import com.vigia.app.analyze.Hallazgo
import com.vigia.app.analyze.Severidad
import com.vigia.app.collect.inventory.InventarioResumen
import com.vigia.app.util.Formato
import org.json.JSONObject

/**
 * Correlación entre módulos: sube la confianza cuando varias señales coinciden en una ventana de
 * tiempo (60 s por defecto) o sobre la misma app. La puntuación es una suma ponderada visible:
 * gravedad × confianza de cada señal.
 */
object Correlacion {

    private fun categoria(regla: String): String? {
        return when (regla) {
            "SIG", "BASE", "H1", "H2", "H7", "H8", "H10", "H11" -> "RED"
            "H4" -> "SENSOR"
            "H3" -> "PANTALLA"
            "H5", "H6", "H9" -> "SISTEMA"
            else -> null
        }
    }

    fun analizar(d: DatosSesion, hallazgos: List<Hallazgo>): List<Hallazgo> {
        val res = ArrayList<Hallazgo>()
        val ventana = d.ajustes.entero(AjustesAnalisis.CORR_VENTANA_S, 60) * 1000L

        // ---- 1) señales de módulos distintos casi a la vez ----
        val candidatos = hallazgos.filter { categoria(it.regla) != null && it.ts > 0L }.sortedBy { it.ts }
        var cluster = ArrayList<Hallazgo>()
        for (h in candidatos) {
            if (cluster.isNotEmpty() && h.ts - cluster[cluster.size - 1].ts > ventana) {
                cerrarCluster(d, cluster, ventana, res)
                cluster = ArrayList()
            }
            cluster.add(h)
        }
        if (cluster.isNotEmpty()) cerrarCluster(d, cluster, ventana, res)

        // ---- 2) app instalada fuera de tienda que además contacta algo sospechoso ----
        val nuevasFueraDeTienda = appsNuevasFueraDeTienda(d)
        val avisadas = HashSet<String>()
        for (h in hallazgos) {
            if (h.regla != "SIG" && h.regla != "H2" && h.regla != "H7") continue
            if (h.app.isEmpty() || !nuevasFueraDeTienda.contains(h.app)) continue
            if (!avisadas.add(h.app)) continue
            res.add(
                Hallazgo(
                    h.ts, Severidad.ALTA, "corr", "CORR", h.app,
                    "App recién instalada fuera de tienda con actividad sospechosa",
                    "La app " + h.app + " se instaló durante la sesión sin pasar por una tienda conocida y además " +
                        "dio lugar al hallazgo «" + h.titulo + "» (" + h.regla + ").",
                    h.evidencias, 75
                )
            )
        }
        return res
    }

    private fun cerrarCluster(d: DatosSesion, cluster: List<Hallazgo>, ventana: Long, res: MutableList<Hallazgo>) {
        if (cluster.size < 2) return
        val categorias = HashSet<String>()
        for (h in cluster) {
            val c = categoria(h.regla)
            if (c != null) categorias.add(c)
        }
        if (categorias.size < 2) return

        var puntos = 0.0
        var maxConfianza = 0
        val evidencias = LinkedHashSet<String>()
        val reglas = LinkedHashSet<String>()
        val apps = LinkedHashSet<String>()
        for (h in cluster) {
            puntos += Severidad.peso(h.severidad) * (h.confianza / 100.0)
            if (h.confianza > maxConfianza) maxConfianza = h.confianza
            evidencias.addAll(h.evidencias)
            reglas.add(h.regla)
            if (h.app.isNotEmpty()) apps.add(h.app)
        }

        val inicio = cluster[0].ts
        val fin = cluster[cluster.size - 1].ts
        var sev = if (puntos >= 12.0) Severidad.CRITICA else if (puntos >= 6.0) Severidad.ALTA else Severidad.MEDIA

        // Caso del plan: red anómala + sensor + actividad de pantalla en la misma ventana → crítica.
        val hayPantalla = d.pantalla.any { it.ts >= inicio - ventana && it.ts <= fin + ventana }
        var extra = ""
        if (categorias.contains("RED") && categorias.contains("SENSOR") && hayPantalla) {
            sev = Severidad.CRITICA
            extra = " Además hubo actividad de pantalla en esos mismos minutos."
        }

        val confianza = minOf(95, maxConfianza + 15)
        res.add(
            Hallazgo(
                inicio, sev, "corr", "CORR", if (apps.size == 1) apps.first() else "",
                "Varias señales coinciden en poco tiempo",
                "Entre las " + Formato.hora(inicio) + " y las " + Formato.hora(fin) + " coincidieron señales de " +
                    categorias.size + " tipos distintos (" + reglas.joinToString(" + ") + ")" +
                    (if (apps.isNotEmpty()) " relacionadas con " + apps.take(4).joinToString(", ") else "") + "." + extra +
                    " Puntuación " + String.format("%.1f", puntos) + ". Esto es anómalo: considera investigar.",
                evidencias.toList(), confianza
            )
        )
    }

    private fun appsNuevasFueraDeTienda(d: DatosSesion): Set<String> {
        val nuevas = HashSet<String>()
        for (e in d.sys) {
            if (e.tipo != "paquete_instalado" && e.tipo != "inventario:app_nueva") continue
            val paquete = try {
                JSONObject(e.payload).optString("paquete", "")
            } catch (x: Exception) {
                ""
            }
            if (paquete.isNotEmpty()) nuevas.add(paquete)
        }
        if (nuevas.isEmpty()) return nuevas
        val instaladores = HashMap<String, String>()
        val apps = d.ultimoInventario?.optJSONArray("apps")
        if (apps != null) {
            for (i in 0 until apps.length()) {
                val a = apps.optJSONObject(i) ?: continue
                instaladores[a.optString("p")] = a.optString("i")
            }
        }
        val fuera = HashSet<String>()
        for (p in nuevas) {
            val inst = instaladores[p]
            if (inst == null || !InventarioResumen.TIENDAS.contains(inst)) fuera.add(p)
        }
        return fuera
    }
}
