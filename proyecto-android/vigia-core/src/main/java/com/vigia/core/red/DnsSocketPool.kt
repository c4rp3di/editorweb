package com.vigia.core.red

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Reutiliza un socket UDP por (red, servidor) en vez de abrir y cerrar uno nuevo en cada consulta DNS. Un hilo
 * lector por socket demultiplexa las respuestas por el ID de transacción DNS (los dos primeros bytes del
 * mensaje), así que varias consultas pueden ir a la vez por el mismo socket sin mezclarse entre sí.
 *
 * Caso borde: si dos consultas al MISMO servidor coinciden exactamente en el mismo ID de transacción (1 entre
 * 65536, al azar), la segunda sobrescribe el hueco de espera de la primera y esta expira por tiempo en vez de
 * recibir respuesta — se reintenta como cualquier otro timeout, nunca se entrega una respuesta equivocada.
 */
class DnsSocketPool(private val protector: (DatagramSocket) -> Unit) {
    private class Entrada(val socket: DatagramSocket) {
        val pendientes = ConcurrentHashMap<Int, ArrayBlockingQueue<ByteArray?>>()

        @Volatile
        var vivo = true
    }

    private val entradas = ConcurrentHashMap<String, Entrada>()

    private fun clave(red: Network?, servidor: InetAddress) = (red?.toString() ?: "-") + "|" + servidor.hostAddress

    /** Envía [consulta] y espera la respuesta hasta [timeoutMs]. null si no llega o falla el socket. */
    fun consultar(red: Network?, servidor: InetAddress, consulta: ByteArray, timeoutMs: Long): ByteArray? {
        if (consulta.size < 2) return null
        val clave = clave(red, servidor)
        val e = entradas[clave]?.takeIf { it.vivo } ?: abrir(clave, red, servidor) ?: return null
        val id = ((consulta[0].toInt() and 0xFF) shl 8) or (consulta[1].toInt() and 0xFF)
        val cola = ArrayBlockingQueue<ByteArray?>(1)
        e.pendientes[id] = cola
        try {
            e.socket.send(DatagramPacket(consulta, consulta.size, servidor, 53))
            return cola.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (ex: Exception) {
            cerrar(clave, e)
            return null
        } finally {
            e.pendientes.remove(id, cola)
        }
    }

    private fun abrir(clave: String, red: Network?, servidor: InetAddress): Entrada? {
        synchronized(entradas) {
            val existente = entradas[clave]
            if (existente != null && existente.vivo) return existente
            return try {
                val s = DatagramSocket()
                if (red != null) red.bindSocket(s) else protector(s)
                val e = Entrada(s)
                entradas[clave] = e
                Thread({ receptor(clave, e) }, "dns-pool").apply { isDaemon = true }.start()
                e
            } catch (ex: Exception) {
                null
            }
        }
    }

    private fun receptor(clave: String, e: Entrada) {
        val buf = ByteArray(4096)
        try {
            while (e.vivo) {
                val p = DatagramPacket(buf, buf.size)
                e.socket.receive(p)
                if (p.length < 2) continue
                val id = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                e.pendientes[id]?.offer(p.data.copyOf(p.length))
            }
        } catch (ex: Exception) {
        } finally {
            cerrar(clave, e)
        }
    }

    private fun cerrar(clave: String, e: Entrada) {
        e.vivo = false
        entradas.remove(clave, e)
        try {
            e.socket.close()
        } catch (ex: Exception) {
        }
        for (q in e.pendientes.values) q.offer(null)
    }

    fun cerrarTodo() {
        for (entry in ArrayList(entradas.entries)) cerrar(entry.key, entry.value)
    }
}
