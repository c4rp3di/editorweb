package com.vigia.app.analyze.heur

import com.vigia.app.analyze.AjustesAnalisis
import com.vigia.app.analyze.DatosSesion
import com.vigia.app.analyze.Hallazgo
import com.vigia.app.analyze.Severidad
import com.vigia.app.analyze.sig.IndiceIoc
import com.vigia.app.store.DnsEventEntity
import com.vigia.app.util.Formato
import org.json.JSONObject
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reglas simples y explicables H1 a H10. Cada una se puede activar o desactivar por separado.
 * Cada hallazgo guarda sus pruebas (identificadores de eventos) y una confianza honesta: la mayoría
 * de estas señales tienen explicaciones inocentes y por eso la confianza es moderada.
 */
object MotorHeuristicas {
    private const val HORA_MS = 3_600_000L
    private const val MAX_POR_REGLA = 40

    fun analizar(d: DatosSesion, idx: IndiceIoc): List<Hallazgo> {
        val res = ArrayList<Hallazgo>()
        val a = d.ajustes
        if (a.activa("H1")) h1(d, res)
        if (a.activa("H2")) h2(d, res)
        if (a.activa("H3")) h3(d, res)
        if (a.activa("H4")) h4(d, res)
        if (a.activa("H5")) h5(d, res)
        if (a.activa("H6")) h6(d, res)
        if (a.activa("H7")) h7(d, idx, res)
        if (a.activa("H8")) h8(d, res)
        if (a.activa("H9")) h9(d, res)
        if (a.activa("H10")) h10(d, res)
        return res
    }

    private fun nota(texto: String): Hallazgo {
        return Hallazgo(0L, Severidad.INFO, "heur", "NOTA", "", "Regla no evaluable", texto, emptyList(), 100)
    }

    private fun campo(payload: String, clave: String): String {
        return try {
            JSONObject(payload).optString(clave, "")
        } catch (e: Exception) {
            ""
        }
    }

    private fun recortar(res: MutableList<Hallazgo>, desde: Int) {
        // Si una regla genera demasiados hallazgos, se conservan los primeros del bloque.
        val bloque = res.size - desde
        if (bloque > MAX_POR_REGLA) {
            val sobran = bloque - MAX_POR_REGLA
            for (i in 0 until sobran) res.removeAt(res.size - 1)
        }
    }

    // ---------------------------------------------------------------- H1
    private fun h1(d: DatosSesion, res: MutableList<Hallazgo>) {
        if (!d.vpn) {
            res.add(nota("H1 no se pudo evaluar: la sesión no tenía activada la captura DNS."))
            return
        }
        if (!d.modulos.eventos) {
            res.add(nota("H1 no se pudo evaluar: hace falta el módulo «Eventos del sistema» para saber cuándo estaba la pantalla encendida."))
            return
        }
        val inicioFranja = d.ajustes.entero(AjustesAnalisis.H1_INICIO, 2)
        val finFranja = d.ajustes.entero(AjustesAnalisis.H1_FIN, 5)
        val off = TimeZone.getDefault().getOffset(d.inicio).toLong()

        val interacciones = ArrayList<Long>()
        val pantalla = ArrayList<Pair<Long, Boolean>>()
        for (e in d.sys) {
            when (e.tipo) {
                "pantalla_encendida" -> {
                    interacciones.add(e.ts)
                    pantalla.add(Pair(e.ts, true))
                }
                "pantalla_apagada" -> pantalla.add(Pair(e.ts, false))
                "desbloqueo" -> interacciones.add(e.ts)
            }
        }
        for (e in d.pantalla) {
            if (e.tipoEvento == "clic") interacciones.add(e.ts)
        }
        interacciones.sort()

        class Acumulado(val ts: Long, val app: String) {
            var cuenta = 0
            val destinos = LinkedHashSet<String>()
            val ids = ArrayList<String>()
        }

        val grupos = LinkedHashMap<String, Acumulado>()

        fun evaluar(ts: Long, app: String, destino: String, evidencia: String) {
            val hora = Math.floorMod(Math.floorDiv(ts + off, HORA_MS), 24L).toInt()
            val dentro = if (inicioFranja <= finFranja) {
                hora >= inicioFranja && hora < finFranja
            } else {
                hora >= inicioFranja || hora < finFranja
            }
            if (!dentro) return
            if (pantallaEncendida(pantalla, ts)) return
            val ultima = ultimoAntes(interacciones, ts)
            if (ultima != null && ts - ultima < 10L * 60_000L) return
            val dia = Math.floorDiv(ts + off - inicioFranja * HORA_MS, 86_400_000L)
            val g = grupos.getOrPut(app + "|" + dia) { Acumulado(ts, app) }
            g.cuenta++
            if (g.destinos.size < 6) g.destinos.add(destino)
            if (g.ids.size < 20) g.ids.add(evidencia)
        }

        for (e in d.dns) evaluar(e.ts, e.app, e.dominio, "dns:" + e.id)
        if (d.red) {
            for (e in d.net) evaluar(e.ts, e.app, if (e.sni.isNotEmpty()) e.sni else e.ip, "net:" + e.id)
        }

        val desde = res.size
        for (g in grupos.values.sortedByDescending { it.cuenta }) {
            val sev = if (g.cuenta >= 20) Severidad.MEDIA else Severidad.BAJA
            res.add(
                Hallazgo(
                    g.ts, sev, "heur", "H1", g.app,
                    "Consultas nocturnas sin interacción",
                    "La app " + g.app + " tuvo " + g.cuenta + " consultas DNS" + (if (d.red) " o conexiones" else "") +
                        " entre las " + inicioFranja + ":00 y las " + finFranja +
                        ":00, con la pantalla apagada y sin que tocaras el teléfono en los 10 minutos previos. Destinos: " +
                        g.destinos.joinToString(", ") + ".",
                    g.ids, 35
                )
            )
        }
        recortar(res, desde)
    }

    private fun ultimoAntes(ordenados: List<Long>, ts: Long): Long? {
        var lo = 0
        var hi = ordenados.size - 1
        var mejor: Long? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (ordenados[mid] <= ts) {
                mejor = ordenados[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return mejor
    }

    private fun pantallaEncendida(eventos: List<Pair<Long, Boolean>>, ts: Long): Boolean {
        if (eventos.isEmpty()) return false
        var lo = 0
        var hi = eventos.size - 1
        var indice = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (eventos[mid].first <= ts) {
                indice = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (indice < 0) return !eventos[0].second
        return eventos[indice].second
    }

    // ---------------------------------------------------------------- H2
    private fun h2(d: DatosSesion, res: MutableList<Hallazgo>) {
        if (!d.vpn) {
            res.add(nota("H2 no se pudo evaluar: la sesión no tenía activada la captura DNS."))
            return
        }
        if (d.dominiosVistos.isEmpty()) {
            res.add(nota("H2 en aprendizaje: todavía no hay dominios de sesiones anteriores con los que comparar."))
            return
        }
        val porApp = LinkedHashMap<String, MutableList<DnsEventEntity>>()
        val cacheLanzador = HashMap<String, Boolean>()
        for (e in d.dns) {
            if (d.dominiosVistos.contains(e.dominio)) continue
            if (e.app == "android" || e.app == "desconocida" || e.app.startsWith("uid:")) continue
            val sinLanzador = cacheLanzador.getOrPut(e.app) { d.sinLanzador(e.app) }
            if (!sinLanzador) continue
            porApp.getOrPut(e.app) { ArrayList() }.add(e)
        }
        val desde = res.size
        for ((app, lista) in porApp) {
            val dominios = lista.map { it.dominio }.distinct()
            res.add(
                Hallazgo(
                    lista[0].ts, Severidad.BAJA, "heur", "H2", app,
                    "Dominio nuevo de una app sin interfaz",
                    "La app " + app + ", que no se abre con un icono, contactó " + dominios.size +
                        " dominio(s) que no se habían visto antes: " + dominios.take(6).joinToString(", ") + ".",
                    d.evidenciasDns(lista), 30
                )
            )
        }
        recortar(res, desde)
    }

    // ---------------------------------------------------------------- H3
    private fun h3(d: DatosSesion, res: MutableList<Hallazgo>) {
        if (!d.modulos.a11y) {
            res.add(nota("H3 no se pudo evaluar: hace falta el módulo «Actividad en pantalla» para saber qué apps se ven."))
            return
        }
        if (!d.modulos.inventario || d.ultimoInventario == null) {
            res.add(nota("H3 no se pudo evaluar: hace falta el módulo «Inventario» para saber qué apps tienen accesibilidad."))
            return
        }
        if (d.pantalla.isEmpty()) {
            res.add(nota("H3 no se pudo evaluar: el servicio de accesibilidad de Vigía no registró nada (¿estaba activado en Ajustes?)."))
            return
        }
        if (d.fin - d.inicio < 60L * 60_000L) {
            res.add(nota("H3 no se pudo evaluar: la sesión duró menos de una hora."))
            return
        }
        val inv = d.ultimoInventario ?: return
        val servicios = inv.optJSONArray("accesibilidad")
        val apps = inv.optJSONArray("apps")
        val sistema = HashSet<String>()
        if (apps != null) {
            for (i in 0 until apps.length()) {
                val a = apps.optJSONObject(i) ?: continue
                if (a.optInt("s") == 1) sistema.add(a.optString("p"))
            }
        }
        val ventanas = HashMap<String, Int>()
        for (e in d.pantalla) {
            if (e.tipoEvento == "ventana") ventanas[e.app] = (ventanas[e.app] ?: 0) + 1
        }
        val consultas = HashMap<String, Int>()
        for (e in d.dns) consultas[e.app] = (consultas[e.app] ?: 0) + 1

        val avisados = HashSet<String>()
        val desde = res.size
        if (servicios != null) {
            for (i in 0 until servicios.length()) {
                val paquete = servicios.optString(i).substringBefore('/')
                if (paquete.isEmpty() || paquete == d.paquetePropio || sistema.contains(paquete)) continue
                if (!avisados.add(paquete)) continue
                if ((ventanas[paquete] ?: 0) > 0) continue
                val dns = consultas[paquete] ?: 0
                val sev = if (dns > 0) Severidad.MEDIA else Severidad.BAJA
                res.add(
                    Hallazgo(
                        inv.optLong("ts", d.fin), sev, "heur", "H3", paquete,
                        "Accesibilidad activa sin uso visible",
                        "La app " + paquete + " tiene un servicio de accesibilidad activo pero no apareció en pantalla durante la sesión" +
                            (if (dns > 0) " y generó " + dns + " consultas DNS." else "."),
                        emptyList(), if (dns > 0) 50 else 40
                    )
                )
            }
        }
        recortar(res, desde)
    }

    // ---------------------------------------------------------------- H4
    private fun h4(d: DatosSesion, res: MutableList<Hallazgo>) {
        if (!d.modulos.sensores) {
            res.add(nota("H4 no se pudo evaluar: la sesión no tenía activado el módulo «Cámara y micrófono»."))
            return
        }
        val ultimo = HashMap<String, Long>()
        val desde = res.size
        for (e in d.sensores) {
            if (!e.modo.startsWith("en_uso")) continue
            if (!e.modo.contains("pantalla apagada")) continue
            if (e.sensor == "microfono" && e.modo.contains("llamada o VoIP")) continue
            val previo = ultimo[e.sensor]
            if (previo != null && e.ts - previo < 60_000L) continue
            ultimo[e.sensor] = e.ts
            val camara = e.sensor == "camara"
            res.add(
                Hallazgo(
                    e.ts, if (camara) Severidad.ALTA else Severidad.MEDIA, "heur", "H4", e.app,
                    if (camara) "Cámara en uso con la pantalla apagada" else "Micrófono en uso con la pantalla apagada",
                    (if (camara) "La cámara" else "El micrófono") + " pasó a estar en uso a las " + Formato.hora(e.ts) +
                        " con la pantalla apagada (" + e.modo + "). App: " + e.app + ".",
                    listOf("sensor:" + e.id), if (camara) 60 else 50
                )
            )
        }
        recortar(res, desde)
    }

    // ---------------------------------------------------------------- H5
    private fun h5(d: DatosSesion, res: MutableList<Hallazgo>) {
        val desde = res.size
        var hayInventario = false
        val certInventario = ArrayList<Long>()
        for (e in d.sys) {
            if (e.tipo == "inventario:certificado_usuario_nuevo") {
                hayInventario = true
                certInventario.add(e.ts)
                res.add(
                    Hallazgo(
                        e.ts, Severidad.ALTA, "heur", "H5", "",
                        "Certificado de usuario nuevo",
                        "Apareció un certificado instalado por el usuario (huella " + campo(e.payload, "huella") + ").",
                        listOf("sys:" + e.id), 75
                    )
                )
            }
        }
        for (e in d.sys) {
            if (e.tipo != "certificados_cambiaron") continue
            val explicado = certInventario.any { abs(it - e.ts) < 10L * 60_000L }
            if (explicado) continue
            res.add(
                Hallazgo(
                    e.ts, Severidad.MEDIA, "heur", "H5", "",
                    "Cambió el almacén de certificados",
                    "Android avisó de un cambio en los certificados de confianza a las " + Formato.hora(e.ts) + ".",
                    listOf("sys:" + e.id), 50
                )
            )
        }
        val primero = d.primerInventario
        val certs = primero?.optJSONArray("certs")
        if (primero != null && certs != null && certs.length() > 0) {
            res.add(
                Hallazgo(
                    primero.optLong("ts", d.inicio), Severidad.INFO, "heur", "H5", "",
                    "Certificados de usuario ya instalados",
                    "Al empezar la sesión había " + certs.length() + " certificado(s) instalado(s) por el usuario. " +
                        "Comprueba en Ajustes › Seguridad que los reconoces.",
                    emptyList(), 100
                )
            )
        }
        if (!hayInventario && !d.modulos.inventario && !d.modulos.eventos) {
            res.add(nota("H5 no se pudo evaluar: hacen falta los módulos «Inventario» o «Eventos del sistema»."))
        }
        recortar(res, desde)
    }

    // ---------------------------------------------------------------- H6
    private fun h6(d: DatosSesion, res: MutableList<Hallazgo>) {
        if (!d.modulos.inventario) {
            res.add(nota("H6 no se pudo evaluar: hace falta el módulo «Inventario»."))
            return
        }
        val desde = res.size
        for (e in d.sys) {
            if (e.tipo != "inventario:administrador_activado") continue
            val paquete = campo(e.payload, "paquete")
            res.add(
                Hallazgo(
                    e.ts, Severidad.ALTA, "heur", "H6", paquete,
                    "Administrador del dispositivo activado",
                    "La app " + paquete + " obtuvo permisos de administrador del dispositivo.",
                    listOf("sys:" + e.id), 70
                )
            )
        }
        val primero = d.primerInventario
        val admins = primero?.optJSONArray("admins")
        if (primero != null && admins != null) {
            for (i in 0 until admins.length()) {
                val paquete = admins.optString(i).substringBefore('/')
                if (paquete.isEmpty()) continue
                res.add(
                    Hallazgo(
                        primero.optLong("ts", d.inicio), Severidad.INFO, "heur", "H6", paquete,
                        "Administrador del dispositivo ya activo",
                        "Al empezar la sesión " + paquete + " ya era administrador del dispositivo. " +
                            "Puede ser legítimo (correo de empresa, localizador del móvil).",
                        emptyList(), 100
                    )
                )
            }
        }
        recortar(res, desde)
    }

    // ---------------------------------------------------------------- H7
    private fun h7(d: DatosSesion, idx: IndiceIoc, res: MutableList<Hallazgo>) {
        if (!d.vpn) {
            res.add(nota("H7 no se pudo evaluar: la sesión no tenía activada la captura DNS."))
            return
        }
        if (idx.vacio) {
            res.add(nota("H7 y las firmas no se pudieron evaluar: no hay indicadores cargados (Ajustes › Indicadores)."))
            return
        }
        class Nombre(val ts: Long, val app: String, val dominio: String, val evidencia: String)
        val nombres = ArrayList<Nombre>()
        for (e in d.dns) nombres.add(Nombre(e.ts, e.app, e.dominio, "dns:" + e.id))
        if (d.red) {
            for (e in d.net) {
                if (e.sni.isNotEmpty()) nombres.add(Nombre(e.ts, e.app, e.sni, "net:" + e.id))
            }
        }
        val vistos = HashSet<String>()
        val desde = res.size
        for (n in nombres) {
            if (!vistos.add(n.dominio)) continue
            if (idx.buscarDominio(n.dominio) != null) continue
            val ioc = idx.parecido(n.dominio) ?: continue
            res.add(
                Hallazgo(
                    n.ts, Severidad.MEDIA, "heur", "H7", n.app,
                    "Dominio parecido a un indicador conocido",
                    "La app " + n.app + " usó " + n.dominio + ", que comparte nombre con el indicador " + ioc +
                        " pero tiene otra terminación. Puede ser una coincidencia inocente.",
                    listOf(n.evidencia), 45
                )
            )
        }
        recortar(res, desde)
    }

    // ---------------------------------------------------------------- H8 (variante DNS)
    private fun h8(d: DatosSesion, res: MutableList<Hallazgo>) {
        if (!d.vpn) {
            res.add(nota("H8 no se pudo evaluar: la sesión no tenía activada la captura DNS."))
            return
        }
        val minimo = d.ajustes.entero(AjustesAnalisis.H8_MIN_CONSULTAS, 12)
        val grupos = LinkedHashMap<String, MutableList<DnsEventEntity>>()
        val desdeVolumen = res.size
        if (d.red) {
            val umbral = d.ajustes.entero(AjustesAnalisis.H8_MB, 20) * 1_048_576L
            class Volumen(val ts: Long, val app: String, val ip: String) {
                var salida = 0L
                var entrada = 0L
                val ids = ArrayList<String>()
            }
            val porDestino = LinkedHashMap<String, Volumen>()
            for (e in d.net) {
                val v = porDestino.getOrPut(e.app + "|" + e.ip) { Volumen(e.ts, e.app, e.ip) }
                v.salida += e.bytesOut
                v.entrada += e.bytesIn
                if (v.ids.size < 20) v.ids.add("net:" + e.id)
            }
            for (v in porDestino.values.sortedByDescending { it.salida }) {
                if (v.salida < umbral) continue
                res.add(
                    Hallazgo(
                        v.ts, Severidad.MEDIA, "heur", "H8", v.app,
                        "Volumen alto de datos hacia una misma IP",
                        "La app " + v.app + " envió " + String.format("%.1f", v.salida / 1_048_576.0) + " MB y recibió " +
                            String.format("%.1f", v.entrada / 1_048_576.0) + " MB con la IP " + v.ip + " durante la sesión.",
                        v.ids, 40
                    )
                )
            }
            recortar(res, desdeVolumen)
        }
        for (e in d.dns) {
            grupos.getOrPut(e.app + "|" + e.dominio) { ArrayList() }.add(e)
        }
        val desde = res.size
        for ((_, lista) in grupos) {
            if (lista.size < minimo) continue
            val intervalos = ArrayList<Double>()
            for (i in 1 until lista.size) intervalos.add((lista[i].ts - lista[i - 1].ts).toDouble())
            var suma = 0.0
            for (x in intervalos) suma += x
            val media = suma / intervalos.size
            if (media < 20_000.0 || media > 3_600_000.0) continue
            var cuadros = 0.0
            for (x in intervalos) cuadros += (x - media) * (x - media)
            val cv = sqrt(cuadros / intervalos.size) / media
            if (cv >= 0.15) continue
            val primero = lista[0]
            res.add(
                Hallazgo(
                    primero.ts, Severidad.BAJA, "heur", "H8", primero.app,
                    "Consultas periódicas muy regulares",
                    "La app " + primero.app + " consultó " + primero.dominio + " " + lista.size + " veces cada " +
                        String.format("%.0f", media / 1000.0) + " s de media, con un ritmo casi idéntico.",
                    d.evidenciasDns(lista), 30
                )
            )
        }
        recortar(res, desde)
    }

    // ---------------------------------------------------------------- H9
    private fun h9(d: DatosSesion, res: MutableList<Hallazgo>) {
        if (!d.modulos.inventario) {
            res.add(nota("H9 no se pudo evaluar: hace falta el módulo «Inventario»."))
            return
        }
        val desde = res.size
        for (e in d.sys) {
            if (e.tipo != "inventario:firma_cambiada") continue
            val paquete = campo(e.payload, "paquete")
            res.add(
                Hallazgo(
                    e.ts, Severidad.ALTA, "heur", "H9", paquete,
                    "Cambió la firma de una app instalada",
                    "La firma de la app " + paquete + " no coincide con la de la foto anterior del inventario.",
                    listOf("sys:" + e.id), 80
                )
            )
        }
        recortar(res, desde)
    }

    // ---------------------------------------------------------------- H10
    private fun h10(d: DatosSesion, res: MutableList<Hallazgo>) {
        if (!d.vpn) {
            res.add(nota("H10 no se pudo evaluar: la sesión no tenía activada la captura DNS."))
            return
        }
        if (d.dominiosVistos.isEmpty()) {
            res.add(nota("H10 en aprendizaje: todavía no hay dominios de sesiones anteriores con los que comparar."))
            return
        }
        val vistos = HashSet<String>()
        val desde = res.size
        if (d.red) {
            for (e in d.net) {
                val delSistema = e.app == "android" || (e.uid in 0..9999)
                if (!delSistema || e.sni.isEmpty() || d.dominiosVistos.contains(e.sni)) continue
                if (!vistos.add(e.app + "|" + e.sni)) continue
                res.add(
                    Hallazgo(
                        e.ts, Severidad.BAJA, "heur", "H10", e.app,
                        "Proceso del sistema con un dominio nuevo",
                        "Un proceso del sistema (" + e.app + ") se conectó a " + e.sni + ", que no se había visto antes.",
                        listOf("net:" + e.id), 35
                    )
                )
            }
        }
        for (e in d.dns) {
            val delSistema = e.app == "android" || (e.uid in 0..9999)
            if (!delSistema) continue
            if (d.dominiosVistos.contains(e.dominio)) continue
            if (!vistos.add(e.app + "|" + e.dominio)) continue
            res.add(
                Hallazgo(
                    e.ts, Severidad.BAJA, "heur", "H10", e.app,
                    "Proceso del sistema con un dominio nuevo",
                    "Un proceso del sistema (" + e.app + ") consultó " + e.dominio + ", que no se había visto antes.",
                    listOf("dns:" + e.id), 35
                )
            )
        }
        recortar(res, desde)
    }
}
