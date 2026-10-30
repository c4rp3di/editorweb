package com.vigia.adblock

import android.content.Context
import com.vigia.core.red.EventoDns
import com.vigia.core.red.EventoRed
import com.vigia.core.util.Formato

/**
 * Registro del Laboratorio (lo que antes era NetLens): quién hace qué conexión. Todo vive en memoria y se pierde al
 * cerrarse el proceso; solo sale del móvil si el usuario pulsa «Exportar». No guarda contenido, solo metadatos:
 * app, nombre consultado, destino, bytes.
 *
 * Mientras [activo] sea falso el túnel no resuelve la app de origen ni lee respuestas: es el modo ligero de siempre.
 */
object Laboratorio {

    @Volatile
    var activo: Boolean = false
        private set

    @Volatile
    var desde: Long = 0L
        private set

    class Linea(val ts: Long, val tipo: String, val app: String, val texto: String, val bloqueado: Boolean)

    class FilaApp(
        val app: String,
        val consultasDns: Int,
        val bloqueadas: Int,
        val bytesIn: Long,
        val bytesOut: Long,
        val httpClaro: Int,
        val destinos: List<Pair<String, Long>>,
        val dominiosDns: List<String>
    ) {
        val bytes: Long get() = bytesIn + bytesOut
    }

    private class Acumulado {
        var consultasDns = 0
        var bloqueadas = 0
        var bytesIn = 0L
        var bytesOut = 0L
        var httpClaro = 0
        val destinos = HashMap<String, Long>()
        val dominiosDns = HashSet<String>()
    }

    private const val MAX_LINEAS = 1500
    private const val MAX_POR_APP = 300

    private val cerrojo = Any()
    private val lineas = ArrayDeque<Linea>()
    private val apps = HashMap<String, Acumulado>()

    fun configurar(c: Context) {
        activo = Ajustes.laboratorio(c)
        if (activo && desde == 0L) desde = System.currentTimeMillis()
    }

    fun activar(c: Context, v: Boolean) {
        Ajustes.setLaboratorio(c, v)
        activo = v
        if (v && desde == 0L) desde = System.currentTimeMillis()
    }

    fun limpiar() {
        synchronized(cerrojo) {
            lineas.clear()
            apps.clear()
            desde = if (activo) System.currentTimeMillis() else 0L
        }
    }

    // ------------------------------------------------------------------ entrada (hilos del túnel: rápido y sin bloquear)

    fun registrarDns(e: EventoDns) {
        if (!activo) return
        synchronized(cerrojo) {
            val a = apps.getOrPut(e.app) { Acumulado() }
            a.consultasDns++
            if (e.bloqueado) a.bloqueadas++
            if (a.dominiosDns.size < MAX_POR_APP) a.dominiosDns.add(e.dominio)
            anotar(Linea(e.ts, "DNS " + e.tipo, e.app, e.dominio + " → " + e.respuesta, e.bloqueado))
        }
    }

    fun registrarConexion(e: EventoRed) {
        if (!activo) return
        synchronized(cerrojo) {
            val a = apps.getOrPut(e.app) { Acumulado() }
            a.bytesIn += e.bytesIn
            a.bytesOut += e.bytesOut
            val nombre = if (e.sni.isNotEmpty()) e.sni else e.ip
            if (a.destinos.containsKey(nombre) || a.destinos.size < MAX_POR_APP) {
                a.destinos[nombre] = (a.destinos[nombre] ?: 0L) + e.bytesIn + e.bytesOut
            }
            // Puerto 80 = HTTP sin cifrar: cualquiera en la red puede leerlo. Es una pista; aquí no se mira el contenido.
            if (e.puerto == 80 && e.proto.startsWith("TCP") && !e.proto.contains("bloqueada")) a.httpClaro++
            val bloqueada = e.proto.contains("bloqueada") || e.proto.contains("rechazada")
            anotar(
                Linea(
                    e.ts, e.proto, e.app,
                    nombre + ":" + e.puerto + "  ↑" + Formato.bytes(e.bytesOut) + " ↓" + Formato.bytes(e.bytesIn),
                    bloqueada
                )
            )
        }
    }

    /** Anotación suelta (por ejemplo, un bloqueo del descifrado MitM). */
    fun nota(tipo: String, app: String, texto: String, bloqueado: Boolean) {
        if (!activo) return
        synchronized(cerrojo) { anotar(Linea(System.currentTimeMillis(), tipo, app, texto, bloqueado)) }
    }

    private fun anotar(l: Linea) {
        lineas.addLast(l)
        while (lineas.size > MAX_LINEAS) lineas.removeFirst()
    }

    // ------------------------------------------------------------------ lectura (hilo principal)

    /** Las [max] líneas más recientes, la más nueva primero. */
    fun recientes(max: Int): List<Linea> {
        synchronized(cerrojo) {
            val res = ArrayList<Linea>(minOf(max, lineas.size))
            var i = lineas.size - 1
            while (i >= 0 && res.size < max) {
                res.add(lineas[i])
                i--
            }
            return res
        }
    }

    /** Resumen por app, de más a menos tráfico. */
    fun resumen(): List<FilaApp> {
        synchronized(cerrojo) {
            val res = ArrayList<FilaApp>(apps.size)
            for ((app, a) in apps) {
                res.add(
                    FilaApp(
                        app, a.consultasDns, a.bloqueadas, a.bytesIn, a.bytesOut, a.httpClaro,
                        a.destinos.entries.sortedByDescending { it.value }.map { Pair(it.key, it.value) },
                        a.dominiosDns.sorted()
                    )
                )
            }
            res.sortWith(compareByDescending<FilaApp> { it.bytes }.thenByDescending { it.consultasDns })
            return res
        }
    }

    fun totalLineas(): Int = synchronized(cerrojo) { lineas.size }

    // ------------------------------------------------------------------ exportar

    /** CSV listo para abrir en una hoja de cálculo. Los textos que empiezan por = + - @ se neutralizan (inyección de fórmulas). */
    fun csv(): String {
        val sb = StringBuilder("﻿")
        sb.append("hora,tipo,app,detalle,bloqueado\n")
        synchronized(cerrojo) {
            for (l in lineas) {
                sb.append(q(Formato.fechaHoraCompleta(l.ts))).append(',')
                    .append(q(l.tipo)).append(',')
                    .append(q(l.app)).append(',')
                    .append(q(l.texto)).append(',')
                    .append(if (l.bloqueado) "si" else "no").append('\n')
            }
        }
        return sb.toString()
    }

    private fun q(s: String): String {
        var v = s.replace("\r", " ").replace("\n", " | ")
        if (v.isNotEmpty() && (v[0] == '=' || v[0] == '+' || v[0] == '-' || v[0] == '@')) v = "'" + v
        return "\"" + v.replace("\"", "\"\"") + "\""
    }
}
