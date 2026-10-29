package com.vigia.adbwifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Un servicio ADB anunciado por mDNS. Solo importa el puerto: se conecta siempre a 127.0.0.1. */
class ServicioMdns(val puerto: Int)

/**
 * Descubre los puertos que Android anuncia por mDNS cuando la depuración inalámbrica está activa. El puerto de
 * emparejamiento solo existe mientras el diálogo «Emparejar con código» está abierto; el de conexión cambia cada
 * vez que se activa la depuración inalámbrica.
 */
object DescubridorMdns {
    private fun esDeEsteTelefono(direccion: java.net.InetAddress?): Boolean {
        if (direccion == null) return false
        if (direccion.isLoopbackAddress) return true
        return try {
            java.net.NetworkInterface.getByInetAddress(direccion) != null
        } catch (e: Exception) {
            false
        }
    }

    const val TIPO_EMPAREJAMIENTO = "_adb-tls-pairing._tcp"
    const val TIPO_CONEXION = "_adb-tls-connect._tcp"

    @Suppress("DEPRECATION")
    suspend fun buscar(contexto: Context, tipo: String, timeoutMs: Long): ServicioMdns? {
        val nsd = contexto.applicationContext.getSystemService(NsdManager::class.java)
        var escucha: NsdManager.DiscoveryListener? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<ServicioMdns?> { cont ->
                    var resolviendo = false
                    val d = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(t: String) {}

                        override fun onStartDiscoveryFailed(t: String, codigo: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onStopDiscoveryFailed(t: String, codigo: Int) {}

                        override fun onDiscoveryStopped(t: String) {}

                        override fun onServiceLost(info: NsdServiceInfo) {}

                        override fun onServiceFound(info: NsdServiceInfo) {
                            if (resolviendo) return
                            resolviendo = true
                            nsd.resolveService(info, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(i: NsdServiceInfo, codigo: Int) {
                                    resolviendo = false
                                }

                                override fun onServiceResolved(i: NsdServiceInfo) {
                                    // En una red compartida pueden anunciarse otros teléfonos: solo vale el servicio de este.
                                    if (esDeEsteTelefono(i.host)) {
                                        if (cont.isActive) cont.resume(ServicioMdns(i.port))
                                    } else {
                                        resolviendo = false
                                    }
                                }
                            })
                        }
                    }
                    escucha = d
                    nsd.discoverServices(tipo, NsdManager.PROTOCOL_DNS_SD, d)
                }
            }
        } finally {
            val e = escucha
            if (e != null) {
                try {
                    nsd.stopServiceDiscovery(e)
                } catch (ex: Exception) {
                }
            }
        }
    }
}
