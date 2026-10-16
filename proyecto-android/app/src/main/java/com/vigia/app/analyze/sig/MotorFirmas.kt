package com.vigia.app.analyze.sig

import com.vigia.app.analyze.DatosSesion
import com.vigia.app.analyze.Hallazgo
import com.vigia.app.analyze.Severidad
import com.vigia.app.store.DnsEventEntity
import com.vigia.app.store.IocEntity
import org.json.JSONObject

/** Índice en memoria de los indicadores para comparar rápido. */
class IndiceIoc(iocs: List<IocEntity>) {
    val dominios = HashMap<String, String>()
    val ips = HashMap<String, String>()
    val paquetes = HashMap<String, String>()
    private val porNombreBase = HashMap<String, String>()

    init {
        for (i in iocs) {
            when (i.tipo) {
                "dominio" -> {
                    dominios[i.valor] = i.fuente
                    val base = etiquetaBase(i.valor)
                    if (base != null && base.length >= 6) porNombreBase[base] = i.valor
                }
                "ip" -> ips[i.valor] = i.fuente
                "paquete" -> paquetes[i.valor] = i.fuente
            }
        }
    }

    val vacio: Boolean get() = dominios.isEmpty() && ips.isEmpty() && paquetes.isEmpty()

    /** Si el dominio (o un dominio padre suyo) es un indicador, devuelve (indicador, fuente). */
    fun buscarDominio(d: String): Pair<String, String>? {
        var actual = d
        while (true) {
            val fuente = dominios[actual]
            if (fuente != null) return Pair(actual, fuente)
            val punto = actual.indexOf('.')
            if (punto < 0) return null
            actual = actual.substring(punto + 1)
            if (!actual.contains('.')) return null
        }
    }

    /** Indicador con el mismo nombre pero distinta terminación, si existe. */
    fun parecido(d: String): String? {
        val base = etiquetaBase(d) ?: return null
        if (base.length < 6) return null
        val ioc = porNombreBase[base] ?: return null
        return if (ioc != d && !d.endsWith("." + ioc)) ioc else null
    }

    companion object {
        private val SEGUNDOS_NIVELES = setOf("co", "com", "org", "net", "gov", "edu", "ac")

        /** Nombre del dominio sin la terminación: «evil-update» para evil-update.com o evil-update.co.uk. */
        fun etiquetaBase(dominio: String): String? {
            val p = dominio.split('.')
            if (p.size < 2) return null
            var i = p.size - 2
            if (p.size >= 3 && p.last().length == 2 && SEGUNDOS_NIVELES.contains(p[p.size - 2])) i = p.size - 3
            return p[i]
        }
    }
}

/** Motor de firmas: compara lo registrado con los indicadores de compromiso. */
object MotorFirmas {

    private class Grupo(
        val ts: Long,
        val app: String,
        val valor: String,
        val ioc: String,
        val fuente: String,
        val ids: MutableList<String>
    ) {
        var cuenta = 0
    }

    fun fuenteCorta(fuente: String): String {
        val limpia = fuente.substringAfterLast('/')
        return if (limpia.isEmpty()) fuente else limpia
    }

    fun analizar(d: DatosSesion, idx: IndiceIoc): List<Hallazgo> {
        val res = ArrayList<Hallazgo>()

        // ---- dominios y direcciones IP en las consultas DNS ----
        val porDominio = LinkedHashMap<String, Grupo>()
        val porIp = LinkedHashMap<String, Grupo>()
        for (e in d.dns) {
            val coincide = idx.buscarDominio(e.dominio)
            if (coincide != null) {
                val clave = e.app + "|" + e.dominio
                val g = porDominio.getOrPut(clave) {
                    Grupo(e.ts, e.app, e.dominio, coincide.first, coincide.second, ArrayList())
                }
                g.cuenta++
                if (g.ids.size < 20) g.ids.add("dns:" + e.id)
            }
            if (idx.ips.isNotEmpty()) buscarIps(e, idx, porIp)
        }

        for (g in porDominio.values) {
            res.add(
                Hallazgo(
                    g.ts, Severidad.ALTA, "sig", "SIG", g.app,
                    "Dominio de una lista de indicadores",
                    "La app " + g.app + " consultó " + g.valor + " (" + g.cuenta + " veces), que coincide con el indicador «" +
                        g.ioc + "» de la lista " + fuenteCorta(g.fuente) + ".",
                    g.ids, 90
                )
            )
        }
        for (g in porIp.values) {
            res.add(
                Hallazgo(
                    g.ts, Severidad.ALTA, "sig", "SIG", g.app,
                    "Dirección IP de una lista de indicadores",
                    "Una respuesta DNS a la app " + g.app + " incluyó la IP " + g.valor + ", presente en la lista " +
                        fuenteCorta(g.fuente) + ". Una IP puede ser compartida por muchos sitios: confianza menor.",
                    g.ids, 65
                )
            )
        }

        // ---- nombres de paquete (apps instaladas o recién instaladas) ----
        if (idx.paquetes.isNotEmpty()) {
            val avisados = HashSet<String>()
            for (e in d.sys) {
                if (e.tipo != "paquete_instalado" && e.tipo != "paquete_actualizado" && e.tipo != "inventario:app_nueva") continue
                val paquete = try {
                    JSONObject(e.payload).optString("paquete", "")
                } catch (x: Exception) {
                    ""
                }
                val fuente = idx.paquetes[paquete] ?: continue
                if (avisados.add(paquete)) res.add(hallazgoPaquete(e.ts, paquete, fuente, listOf("sys:" + e.id), "instalada durante la sesión"))
            }
            val inv = d.ultimoInventario
            val apps = inv?.optJSONArray("apps")
            if (inv != null && apps != null) {
                val ts = inv.optLong("ts", d.fin)
                for (i in 0 until apps.length()) {
                    val paquete = apps.optJSONObject(i)?.optString("p") ?: continue
                    val fuente = idx.paquetes[paquete] ?: continue
                    if (avisados.add(paquete)) res.add(hallazgoPaquete(ts, paquete, fuente, emptyList(), "presente en el teléfono"))
                }
            }
        }
        return res
    }

    private fun hallazgoPaquete(ts: Long, paquete: String, fuente: String, ids: List<String>, donde: String): Hallazgo {
        return Hallazgo(
            ts, Severidad.ALTA, "sig", "SIG", paquete,
            "App con el nombre de un indicador",
            "La app " + paquete + " (" + donde + ") tiene el mismo nombre de paquete que un indicador de la lista " +
                fuenteCorta(fuente) + ".",
            ids, 85
        )
    }

    private fun buscarIps(e: DnsEventEntity, idx: IndiceIoc, destino: MutableMap<String, Grupo>) {
        for (parte in e.respuesta.split(", ")) {
            val ip = parte.trim().lowercase()
            val fuente = idx.ips[ip] ?: continue
            val clave = e.app + "|" + ip
            val g = destino.getOrPut(clave) { Grupo(e.ts, e.app, ip, ip, fuente, ArrayList()) }
            g.cuenta++
            if (g.ids.size < 20) g.ids.add("dns:" + e.id)
        }
    }
}
