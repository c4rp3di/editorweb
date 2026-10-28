package com.lab.netlens

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.Random

fun main() {
    val random = Random(240628L)
    repeat(2000) {
        val bytes = ByteArray(random.nextInt(256))
        random.nextBytes(bytes)
        Dpi.tls(bytes)
    }

    val tmp = File.createTempFile("netlens-precheck", ".bin")
    val good = Engine(object : VpnService() {}, FileOutputStream(tmp), PackageManager(), ConnectivityManager())
    val tcp = SocketChannel.open().socket()
    val udp = DatagramChannel.open().socket()
    good.protect(tcp)
    good.protect(udp)
    tcp.close(); udp.close(); good.stop()

    val bad = Engine(object : VpnService() {
        override fun protect(socket: Socket): Boolean = false
        override fun protect(socket: DatagramSocket): Boolean = false
    }, FileOutputStream(tmp), PackageManager(), ConnectivityManager())
    val tcpBad = SocketChannel.open().socket()
    val tcpMessage = try { bad.protect(tcpBad); "" } catch (e: IOException) { e.message ?: "" }
    check(tcpMessage.contains("Android rechazó el descriptor TCP")) { tcpMessage }
    tcpBad.close()
    val udpBad = DatagramChannel.open().socket()
    val udpMessage = try { bad.protect(udpBad); "" } catch (e: IOException) { e.message ?: "" }
    check(udpMessage.contains("Android rechazó el descriptor UDP")) { udpMessage }
    udpBad.close()

    VpnService.simulatePrepareIntent = true
    val revokedSocket = SocketChannel.open().socket()
    val revokedMessage = try { bad.protect(revokedSocket); "" } catch (e: IOException) { e.message ?: "" }
    check(revokedMessage.contains("permiso VPN no está activo o fue revocado")) { revokedMessage }
    check(Store.lastRuntimeError == revokedMessage) { "El fallo debe quedar visible en el estado" }
    revokedSocket.close()
    VpnService.simulatePrepareIntent = false

    val throwingService = object : VpnService() {
        override fun protect(socket: Socket): Boolean = throw IllegalStateException("stub exception")
    }
    val throwing = Engine(throwingService, FileOutputStream(tmp), PackageManager(), ConnectivityManager())
    val throwingSocket = SocketChannel.open().socket()
    val thrownMessage = try { throwing.protect(throwingSocket); "" } catch (e: IOException) { e.message ?: "" }
    check(thrownMessage.contains("IllegalStateException") && Store.lastRuntimeError == thrownMessage) { thrownMessage }
    throwingSocket.close(); throwing.stop()

    bad.stop(); tmp.delete()
    println("PASS — 2,000 TLS parser fuzz inputs; TCP/UDP protect success, false, revoked-permission diagnosis and thrown-error visibility")
}
