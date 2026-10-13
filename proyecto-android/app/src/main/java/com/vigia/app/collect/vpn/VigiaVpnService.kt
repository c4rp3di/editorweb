package com.vigia.app.collect.vpn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.vigia.app.core.Estado
import com.vigia.app.core.SessionManager
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.DnsEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Estado visible para la interfaz: ¿está la VPN local capturando? */
object VpnEstado {
    val activa = MutableStateFlow(false)
}

/**
 * VPN local en modo DNS (Fase 2A).
 *
 * Solo se enruta hacia el túnel UNA dirección falsa (el servidor DNS 10.77.0.2). Todo el
 * tráfico restante sigue su camino normal, por lo que internet no depende de este código.
 * Cada consulta DNS que llega se registra (dominio, tipo, app de origen, respuesta) y se
 * reenvía al servidor DNS real de la red mediante un socket protegido.
 * No se lee ni se guarda contenido de ninguna comunicación.
 */
class VigiaVpnService : VpnService() {

    private class Ejecucion(
        val interfaz: ParcelFileDescriptor,
        val salida: FileOutputStream,
        val sesionId: Long,
        val pool: ExecutorService,
        val cola: Channel<DnsEventEntity>
    ) {
        @Volatile
        var activa = true
        var hilo: Thread? = null
        var escritor: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bloqueoEscritura = Any()
    private val cacheApps = ConcurrentHashMap<Int, String>()

    @Volatile
    private var ejecucion: Ejecucion? = null

    @Volatile
    private var arrancando = false

    @Volatile
    private var capturando = true

    @Volatile
    private var upstream: InetAddress? = null

    @Volatile
    private var upstreamTs = 0L

    @Volatile
    private var ultimoStartId = 0

    private var jobControl: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ultimoStartId = startId
        if (ejecucion == null && !arrancando) {
            arrancar()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // El usuario (o el sistema) ha apagado la VPN: se cierra la captura.
        jobControl?.cancel()
        val ej = ejecucion
        if (ej != null) limpiar(ej)
        super.onRevoke()
    }

    override fun onDestroy() {
        val ej = ejecucion
        if (ej != null) {
            ej.activa = false
            try {
                ej.interfaz.close()
            } catch (e: IOException) {
            }
            ej.hilo?.interrupt()
            ej.pool.shutdownNow()
            ej.cola.close()
        }
        ejecucion = null
        VpnEstado.activa.value = false
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ arranque

    private fun arrancar() {
        arrancando = true
        jobControl = scope.launch {
            try {
                val gestor = SessionManager.obtener(applicationContext)
                gestor.cargarSiHaceFalta()
                val ui = gestor.estado.value
                val id = ui.sesionId
                if (ui.estado == Estado.INACTIVA || id == null || !ui.vpn) {
                    stopSelf(ultimoStartId)
                    return@launch
                }
                capturando = ui.estado == Estado.ACTIVA
                val ej = abrirTunel(id)
                if (ej == null) {
                    stopSelf(ultimoStartId)
                    return@launch
                }
                ejecucion = ej
                VpnEstado.activa.value = true
                upstream = buscarUpstream()
                upstreamTs = System.currentTimeMillis()
                arrancando = false

                ej.escritor = launch(Dispatchers.IO) { escribirEventos(ej) }
                val entrada = FileInputStream(ej.interfaz.fileDescriptor)
                val hilo = Thread { bucleLectura(ej, entrada) }
                hilo.isDaemon = true
                ej.hilo = hilo
                hilo.start()

                val observador = launch {
                    gestor.estado.collect { u ->
                        if (u.cargado) {
                            capturando = u.estado == Estado.ACTIVA
                        }
                    }
                }
                gestor.estado.first { it.cargado && it.estado == Estado.INACTIVA }
                observador.cancel()
                limpiar(ej)
            } finally {
                arrancando = false
            }
        }
    }

    private fun abrirTunel(sesionId: Long): Ejecucion? {
        val builder = Builder()
            .setSession("Vigía")
            .setMtu(1500)
            .addAddress(IP_CLIENTE, 32)
            .addDnsServer(IP_DNS)
            .addRoute(IP_DNS, 32)
            .setBlocking(true)
        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
        if (pfd == null) return null
        val salida = FileOutputStream(pfd.fileDescriptor)
        val pool = Executors.newFixedThreadPool(6)
        val cola = Channel<DnsEventEntity>(Channel.UNLIMITED)
        return Ejecucion(pfd, salida, sesionId, pool, cola)
    }

    private fun limpiar(ej: Ejecucion) {
        synchronized(ej) {
            if (!ej.activa) return
            ej.activa = false
        }
        if (ejecucion === ej) {
            ejecucion = null
            VpnEstado.activa.value = false
        }
        try {
            ej.interfaz.close()
        } catch (e: IOException) {
        }
        ej.hilo?.interrupt()
        ej.pool.shutdown()
        scope.launch(Dispatchers.IO) {
            try {
                ej.pool.awaitTermination(5, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
            }
            ej.cola.close()
            ej.escritor?.join()
            if (ejecucion == null) stopSelf(ultimoStartId)
        }
    }

    // ------------------------------------------------------------------ lectura del túnel

    private fun bucleLectura(ej: Ejecucion, entrada: FileInputStream) {
        val buf = ByteArray(32767)
        while (ej.activa) {
            val n = try {
                entrada.read(buf)
            } catch (e: IOException) {
                -1
            }
            if (n < 0) break
            if (n == 0) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }
            try {
                procesar(ej, buf, n)
            } catch (e: Exception) {
                // Un paquete mal formado nunca debe parar el bucle.
            }
        }
    }

    private fun procesar(ej: Ejecucion, buf: ByteArray, n: Int) {
        if (n < 20) return
        if ((Paquetes.u8(buf, 0) shr 4) != 4) return
        val ihl = (Paquetes.u8(buf, 0) and 0x0F) * 4
        if (ihl < 20 || n < ihl + 8) return
        val proto = Paquetes.u8(buf, 9)
        val origen = Paquetes.ip4(buf, 12)
        val destino = Paquetes.ip4(buf, 16)
        if (destino != IP_DNS_INT) return

        if (proto == Paquetes.PROTO_UDP) {
            val puertoOrigen = Paquetes.u16(buf, ihl)
            val puertoDestino = Paquetes.u16(buf, ihl + 2)
            if (puertoDestino != 53) return
            val udpLen = Paquetes.u16(buf, ihl + 4)
            val cargaLen = minOf(udpLen - 8, n - ihl - 8)
            if (cargaLen < 12) return
            val consulta = buf.copyOfRange(ihl + 8, ihl + 8 + cargaLen)
            try {
                ej.pool.execute { manejarConsulta(ej, origen, puertoOrigen, consulta) }
            } catch (e: RejectedExecutionException) {
            }
        } else if (proto == Paquetes.PROTO_TCP) {
            if (n < ihl + 20) return
            val flags = Paquetes.u8(buf, ihl + 13)
            val esSyn = (flags and 0x02) != 0 && (flags and 0x10) == 0
            if (esSyn) {
                // DNS sobre TCP / DNS privado (DoT): se rechaza al instante para que el sistema use UDP.
                val puertoOrigen = Paquetes.u16(buf, ihl)
                val puertoDestino = Paquetes.u16(buf, ihl + 2)
                val seq = Paquetes.u32(buf, ihl + 4)
                val rst = Paquetes.construirRstTcp(destino, origen, puertoDestino, puertoOrigen, seq)
                escribirTunel(ej, rst)
            }
        }
    }

    private fun escribirTunel(ej: Ejecucion, paquete: ByteArray) {
        synchronized(bloqueoEscritura) {
            try {
                ej.salida.write(paquete)
            } catch (e: IOException) {
            }
        }
    }

    // ------------------------------------------------------------------ consultas DNS

    private fun manejarConsulta(ej: Ejecucion, origenIp: Int, puertoOrigen: Int, consulta: ByteArray) {
        val ahora = System.currentTimeMillis()
        val q = DnsParser.parseConsulta(consulta)
        val uid = resolverUid(origenIp, puertoOrigen)

        var respuesta: ByteArray? = null
        try {
            val servidor = servidorUpstream()
            val socket = DatagramSocket()
            try {
                protect(socket)
                socket.soTimeout = 4000
                socket.send(DatagramPacket(consulta, consulta.size, servidor, 53))
                val recibido = ByteArray(4096)
                val paquete = DatagramPacket(recibido, recibido.size)
                socket.receive(paquete)
                respuesta = recibido.copyOf(paquete.length)
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            respuesta = null
            upstreamTs = 0L
        }

        if (respuesta != null) {
            val paquete = Paquetes.construirUdp(IP_DNS_INT, origenIp, 53, puertoOrigen, respuesta)
            escribirTunel(ej, paquete)
        }

        if (capturando && q != null) {
            val texto = if (respuesta != null) {
                DnsParser.resumenRespuesta(respuesta)
            } else {
                "sin respuesta (tiempo agotado)"
            }
            ej.cola.trySend(
                DnsEventEntity(
                    sessionId = ej.sesionId,
                    ts = ahora,
                    uid = uid,
                    app = nombreApp(uid),
                    dominio = q.nombre,
                    tipo = DnsParser.nombreTipo(q.tipo),
                    respuesta = texto
                )
            )
        }
    }

    private suspend fun escribirEventos(ej: Ejecucion) {
        val dao = AppDatabase.obtener(applicationContext).dnsDao()
        while (true) {
            val primero = ej.cola.receiveCatching().getOrNull() ?: break
            val lote = ArrayList<DnsEventEntity>()
            lote.add(primero)
            while (lote.size < 200) {
                val otro = ej.cola.tryReceive().getOrNull() ?: break
                lote.add(otro)
            }
            try {
                dao.insertarLote(lote)
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ ayudas

    private fun servidorUpstream(): InetAddress {
        val ahora = System.currentTimeMillis()
        val actual = upstream
        if (actual == null || ahora - upstreamTs > 30000L) {
            val nuevo = buscarUpstream()
            upstream = nuevo
            upstreamTs = ahora
            return nuevo
        }
        return actual
    }

    /** Servidor DNS IPv4 de la red real (no de la VPN). Si no se encuentra, usa 9.9.9.9. */
    private fun buscarUpstream(): InetAddress {
        var primero: InetAddress? = null
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            for (red in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(red) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val lp = cm.getLinkProperties(red) ?: continue
                for (d in lp.dnsServers) {
                    if (d is Inet4Address) {
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return d
                        if (primero == null) primero = d
                    }
                }
            }
        } catch (e: Exception) {
        }
        return primero ?: InetAddress.getByName("9.9.9.9")
    }

    private fun resolverUid(origenIp: Int, puertoOrigen: Int): Int {
        if (Build.VERSION.SDK_INT < 29) return -1
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val local = InetSocketAddress(Paquetes.ipObjeto(origenIp), puertoOrigen)
            val remoto = InetSocketAddress(Paquetes.ipObjeto(IP_DNS_INT), 53)
            cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remoto)
        } catch (e: Exception) {
            -1
        }
    }

    private fun nombreApp(uid: Int): String {
        if (uid < 0) return "desconocida"
        val guardado = cacheApps[uid]
        if (guardado != null) return guardado
        val paquetes = try {
            packageManager.getPackagesForUid(uid)
        } catch (e: Exception) {
            null
        }
        val nombre = if (paquetes != null && paquetes.isNotEmpty()) paquetes[0] else "uid:" + uid
        cacheApps[uid] = nombre
        return nombre
    }

    companion object {
        const val IP_CLIENTE = "10.77.0.1"
        const val IP_DNS = "10.77.0.2"
        val IP_DNS_INT: Int = Paquetes.ip4(byteArrayOf(10, 77, 0, 2), 0)
    }
}
