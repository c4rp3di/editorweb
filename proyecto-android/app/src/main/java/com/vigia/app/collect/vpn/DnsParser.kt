package com.vigia.app.collect.vpn

import java.net.InetAddress

class DnsConsulta(val nombre: String, val tipo: Int)

private class NombreLeido(val nombre: String, val siguiente: Int)

/** Lectura mínima de mensajes DNS (RFC 1035): solo lo necesario para registrar dominio, tipo y respuesta. */
object DnsParser {

    private fun u8(d: ByteArray, i: Int): Int = d[i].toInt() and 0xFF

    private fun u16(d: ByteArray, i: Int): Int = (u8(d, i) shl 8) or u8(d, i + 1)

    private fun leerNombre(d: ByteArray, inicio: Int): NombreLeido? {
        val limite = d.size
        val sb = StringBuilder()
        var pos = inicio
        var siguiente = -1
        var saltos = 0
        while (true) {
            if (pos >= limite) return null
            val len = u8(d, pos)
            if (len == 0) {
                if (siguiente < 0) siguiente = pos + 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= limite) return null
                val puntero = ((len and 0x3F) shl 8) or u8(d, pos + 1)
                if (siguiente < 0) siguiente = pos + 2
                saltos++
                if (saltos > 16) return null
                pos = puntero
                continue
            }
            if (len > 63) return null
            if (pos + 1 + len > limite) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, pos + 1, len, Charsets.ISO_8859_1))
            if (sb.length > 255) return null
            pos += 1 + len
        }
        val nombre = if (sb.isEmpty()) "." else sb.toString().lowercase()
        return NombreLeido(nombre, siguiente)
    }

    fun parseConsulta(d: ByteArray): DnsConsulta? {
        if (d.size < 12) return null
        if ((u8(d, 2) and 0x80) != 0) return null
        if (u16(d, 4) < 1) return null
        val n = leerNombre(d, 12) ?: return null
        if (n.siguiente + 4 > d.size) return null
        return DnsConsulta(n.nombre, u16(d, n.siguiente))
    }

    fun nombreTipo(tipo: Int): String {
        return when (tipo) {
            1 -> "A"
            2 -> "NS"
            5 -> "CNAME"
            6 -> "SOA"
            12 -> "PTR"
            15 -> "MX"
            16 -> "TXT"
            28 -> "AAAA"
            33 -> "SRV"
            64 -> "SVCB"
            65 -> "HTTPS"
            255 -> "ANY"
            else -> "TYPE" + tipo
        }
    }

    /** Resumen legible de la respuesta: IPs y alias, o NXDOMAIN / código de error. */
    fun resumenRespuesta(d: ByteArray): String {
        if (d.size < 12) return "respuesta inválida"
        val rcode = u8(d, 3) and 0x0F
        if (rcode == 3) return "NXDOMAIN"
        if (rcode != 0) return "error DNS (código " + rcode + ")"
        val preguntas = u16(d, 4)
        val respuestas = u16(d, 6)
        var pos = 12
        for (i in 0 until preguntas) {
            val n = leerNombre(d, pos) ?: return "respuesta inválida"
            pos = n.siguiente + 4
            if (pos > d.size) return "respuesta inválida"
        }
        val partes = ArrayList<String>()
        for (i in 0 until respuestas) {
            if (partes.size >= 8) break
            val n = leerNombre(d, pos) ?: break
            pos = n.siguiente
            if (pos + 10 > d.size) break
            val tipo = u16(d, pos)
            val largo = u16(d, pos + 8)
            val datos = pos + 10
            if (datos + largo > d.size) break
            if (tipo == 1 && largo == 4) {
                partes.add(InetAddress.getByAddress(d.copyOfRange(datos, datos + 4)).hostAddress ?: "?")
            } else if (tipo == 28 && largo == 16) {
                partes.add(InetAddress.getByAddress(d.copyOfRange(datos, datos + 16)).hostAddress ?: "?")
            } else if (tipo == 5) {
                val alias = leerNombre(d, datos)
                if (alias != null) partes.add("alias " + alias.nombre)
            }
            pos = datos + largo
        }
        if (partes.isEmpty()) return "(sin direcciones)"
        val texto = partes.joinToString(", ")
        return if (texto.length > 300) texto.substring(0, 300) else texto
    }
}
