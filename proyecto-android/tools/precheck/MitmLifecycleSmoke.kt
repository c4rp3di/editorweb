package com.lab.netlens

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

fun main() {
    val loopback = InetAddress.getByName("127.0.0.1")
    val address = InetSocketAddress(loopback, MitmProxy.PORT)
    val proxy = MitmProxy(MitmProxy.PORT) { true }
    proxy.start()

    // start() must not return until the loopback listener is bound and reachable.
    val client = Socket()
    client.connect(address, 1000)
    client.soTimeout = 500
    check(client.isConnected)

    proxy.stop()
    // Closing the listener must also break an active client connection.
    val peerClosed = try {
        client.getInputStream().read() == -1
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: Exception) {
        true
    } finally {
        try { client.close() } catch (_: Exception) {}
    }
    check(peerClosed) { "El socket cliente activo debe cerrarse al detener MITM" }

    // A new server must be able to bind the same loopback port after stop().
    val probe = ServerSocket()
    try {
        probe.reuseAddress = true
        probe.bind(address, 1)
    } finally {
        try { probe.close() } catch (_: Exception) {}
    }
    println("PASS — MITM listener: bind síncrono, cierre de cliente y liberación del puerto al detener")
}
