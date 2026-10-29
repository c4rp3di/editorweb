package org.nododeverdad.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Radar local de DNS.
 *
 * Crea una VPN que SOLO captura las consultas DNS del móvil (la «agenda de direcciones» que
 * consultan las apps antes de conectarse a un servidor). No se desvía el resto del tráfico,
 * no se descifra nada y nada sale a servidores nuestros: las consultas se reenvían al
 * resolutor DNS que ya usabas.
 *
 * De cada consulta solo interesa el NOMBRE del servidor. Si pertenece a un rastreador
 * conocido, se anota (app, dominio, empresa, número de consultas). Si no, solo se suma a un
 * contador diario, sin guardar el dominio, para no construir un historial de navegación.
 */
class RadarVpnService : VpnService() {

    companion object {
        const val ACTION_START = "org.nododeverdad.app.START"
        const val ACTION_STOP = "org.nododeverdad.app.STOP"
        private const val CHANNEL = "radar"
        private const val NOTIF_ID = 7
        private const val VPN_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.2"

        @Volatile var running: Boolean = false
        @Volatile var lastError: String? = null
        @Volatile var shield: Boolean = false
        @Volatile var shieldCats: Set<String> = emptySet()

        fun loadConfig(ctx: Context) {
            shield = Prefs.shield(ctx)
            shieldCats = Prefs.shieldCats(ctx)
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, RadarVpnService::class.java)
            i.action = ACTION_START
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, RadarVpnService::class.java)
            i.action = ACTION_STOP
            ctx.startService(i)
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var pool: ExecutorService? = null
    private var timer: ScheduledExecutorService? = null
    @Volatile private var stopping: Boolean = false
    @Volatile private var upstream: InetAddress? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            stopRadar()
            return START_NOT_STICKY
        }
        startRadar()
        return START_STICKY
    }

    override fun onRevoke() {
        stopRadar()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ---------- Arranque y parada ----------

    private fun startRadar() {
        if (running) return
        lastError = null
        loadConfig(this)
        showForeground()
        refreshUpstream()

        val builder = Builder()
        builder.setSession("Nodo de Verdad")
        builder.addAddress(VPN_ADDR, 32)
        builder.addRoute(DNS_ADDR, 32)
        builder.addDnsServer(DNS_ADDR)
        builder.setMtu(1500)
        builder.setBlocking(true)

        val fd: ParcelFileDescriptor? = try {
            builder.establish()
        } catch (e: Exception) {
            lastError = "No se pudo crear el radar: " + e.message
            null
        }
        if (fd == null) {
            if (lastError == null) lastError = "Android no permitió crear el radar (¿permiso de VPN retirado?)."
            stopSelf()
            return
        }
        tun = fd
        stopping = false
        running = true
        pool = Executors.newFixedThreadPool(6)

        val st = Store.get(this)
        st.purge(Prefs.retention(this))
        timer = Executors.newSingleThreadScheduledExecutor()
        timer?.scheduleWithFixedDelay({
            try {
                st.flush()
            } catch (e: Exception) {
                // se reintentará en el siguiente ciclo
            }
        }, 2, 2, TimeUnit.SECONDS)
        timer?.scheduleWithFixedDelay({
            try {
                refreshUpstream()
            } catch (e: Exception) {
                // se conserva el anterior
            }
        }, 20, 20, TimeUnit.SECONDS)

        val t = Thread { loop(fd) }
        t.isDaemon = true
        t.name = "nv-radar"
        reader = t
        t.start()
    }

    private fun stopRadar() {
        cleanup()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignorar
        }
        stopSelf()
    }

    private fun cleanup() {
        stopping = true
        running = false
        try {
            timer?.shutdownNow()
        } catch (e: Exception) {
        }
        try {
            pool?.shutdownNow()
        } catch (e: Exception) {
        }
        try {
            reader?.interrupt()
        } catch (e: Exception) {
        }
        try {
            tun?.close()
        } catch (e: Exception) {
        }
        tun = null
        timer = null
        pool = null
        reader = null
        try {
            Store.get(this).flush()
        } catch (e: Exception) {
        }
    }

    // ---------- Notificación obligatoria (servicio en primer plano) ----------

    private fun showForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Radar activo", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Aviso fijo mientras el radar de rastreadores está encendido"
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopI = Intent(this, RadarVpnService::class.java)
        stopI.action = ACTION_STOP
        val stopP = PendingIntent.getService(this, 1, stopI, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("Radar activo")
            .setContentText("Solo mira a quién llama tu móvil. Nada sale de aquí.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Apagar", stopP)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- Resolutor al que se reenvían las consultas ----------

    private fun refreshUpstream() {
        val custom = Prefs.dns(this).trim()
        if (custom.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$"))) {
            try {
                upstream = InetAddress.getByName(custom)
                return
            } catch (e: Exception) {
                // se intenta el de la red
            }
        }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var best: InetAddress? = null
        var bestScore = -1
        @Suppress("DEPRECATION")
        val nets: Array<Network> = cm.allNetworks
        for (n in nets) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            var v4: InetAddress? = null
            for (a in lp.dnsServers) {
                if (a is Inet4Address) {
                    v4 = a
                    break
                }
            }
            if (v4 == null) continue
            val score = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 2 else 1
            if (score > bestScore) {
                bestScore = score
                best = v4
            }
        }
        upstream = best ?: InetAddress.getByName("1.1.1.1")
    }

    // ---------- Bucle de paquetes ----------

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (!stopping) {
            val len: Int = try {
                input.read(buf)
            } catch (e: IOException) {
                break
            }
            if (len < 0) break
            if (len == 0) continue
            try {
                handlePacket(buf, len, output)
            } catch (e: Exception) {
                // un paquete raro no debe tumbar el radar
            }
        }
        if (!stopping) {
            lastError = "El radar se detuvo (Android cerró la VPN)."
            stopRadar()
        }
    }

    private fun handlePacket(p: ByteArray, len: Int, out: FileOutputStream) {
        if (len < 28) return
        val version = (p[0].toInt() ushr 4) and 0xF
        if (version != 4) return
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return
        val proto = p[9].toInt() and 0xFF
        if (proto != 17) return
        val srcPort = ((p[ihl].toInt() and 0xFF) shl 8) or (p[ihl + 1].toInt() and 0xFF)
        val dstPort = ((p[ihl + 2].toInt() and 0xFF) shl 8) or (p[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val udpLen = ((p[ihl + 4].toInt() and 0xFF) shl 8) or (p[ihl + 5].toInt() and 0xFF)
        val payloadLen = Math.min(udpLen - 8, len - ihl - 8)
        if (payloadLen < 12) return
        val payload = p.copyOfRange(ihl + 8, ihl + 8 + payloadLen)
        val srcIp = p.copyOfRange(12, 16)
        val dstIp = p.copyOfRange(16, 20)
        val ex = pool ?: return
        ex.execute {
            try {
                answer(payload, srcIp, dstIp, srcPort, dstPort, out)
            } catch (e: Exception) {
                // consulta perdida: el cliente reintentará
            }
        }
    }

    private fun answer(query: ByteArray, srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, out: FileOutputStream) {
        val name = Dns.questionName(query)
        val qtype = Dns.questionType(query)
        var blockIt = false
        if (name != null && (qtype == 1 || qtype == 28)) {
            val rule = Knowledge.match(name)
            if (rule != null) {
                val pkg = packageFor(srcIp, srcPort, dstIp, dstPort)
                blockIt = shield && shieldCats.contains(rule.cat.key)
                Store.get(this).record(pkg, name, rule.company.id, rule.cat.key, blockIt)
            }
            Store.get(this).tick(blockIt)
        }
        if (blockIt) {
            writeUdp(out, Dns.nxdomain(query), dstIp, srcIp, dstPort, srcPort)
            return
        }
        val up = upstream ?: InetAddress.getByName("1.1.1.1")
        val sock = DatagramSocket()
        try {
            protect(sock)
            sock.soTimeout = 4000
            sock.connect(InetSocketAddress(up, 53))
            sock.send(DatagramPacket(query, query.size))
            val rb = ByteArray(4096)
            val rp = DatagramPacket(rb, rb.size)
            sock.receive(rp)
            writeUdp(out, rb.copyOf(rp.length), dstIp, srcIp, dstPort, srcPort)
        } catch (e: Exception) {
            writeUdp(out, Dns.servfail(query), dstIp, srcIp, dstPort, srcPort)
        } finally {
            sock.close()
        }
    }

    // ---------- ¿Qué app hizo la consulta? ----------

    private fun packageFor(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): String {
        if (Build.VERSION.SDK_INT < 29) return AppInfo.SYSTEM
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val uid = cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
            )
            if (uid >= 0) {
                val pkgs = packageManager.getPackagesForUid(uid)
                if (pkgs != null && pkgs.isNotEmpty()) {
                    val first = pkgs[0]
                    if (first != packageName) return first
                }
            }
        } catch (e: Exception) {
            // sin atribución
        }
        return AppInfo.SYSTEM
    }

    // ---------- Construcción de paquetes IPv4/UDP de respuesta ----------

    private fun writeUdp(out: FileOutputStream, data: ByteArray, srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int) {
        val total = 20 + 8 + data.size
        val b = ByteArray(total)
        b[0] = 0x45.toByte()
        b[2] = (total shr 8).toByte()
        b[3] = (total and 0xFF).toByte()
        b[6] = 0x40.toByte()
        b[8] = 64
        b[9] = 17
        System.arraycopy(srcIp, 0, b, 12, 4)
        System.arraycopy(dstIp, 0, b, 16, 4)
        val udpLen = 8 + data.size
        b[20] = (srcPort shr 8).toByte()
        b[21] = (srcPort and 0xFF).toByte()
        b[22] = (dstPort shr 8).toByte()
        b[23] = (dstPort and 0xFF).toByte()
        b[24] = (udpLen shr 8).toByte()
        b[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(data, 0, b, 28, data.size)
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val ck = sum.inv() and 0xFFFF
        b[10] = (ck shr 8).toByte()
        b[11] = (ck and 0xFF).toByte()
        synchronized(out) {
            out.write(b)
        }
    }
}

/** Utilidades mínimas para leer una consulta DNS y fabricar respuestas de error. */
object Dns {
    fun questionName(q: ByteArray): String? {
        if (q.size < 13) return null
        val qd = ((q[4].toInt() and 0xFF) shl 8) or (q[5].toInt() and 0xFF)
        if (qd < 1) return null
        val sb = StringBuilder()
        var i = 12
        while (i < q.size) {
            val l = q[i].toInt() and 0xFF
            if (l == 0) break
            if ((l and 0xC0) != 0) return null
            if (i + 1 + l > q.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 0 until l) sb.append((q[i + 1 + k].toInt() and 0xFF).toChar())
            i += 1 + l
            if (sb.length > 253) return null
        }
        return if (sb.isEmpty()) null else sb.toString().lowercase()
    }

    fun questionType(q: ByteArray): Int {
        var i = 12
        while (i < q.size) {
            val l = q[i].toInt() and 0xFF
            if (l == 0) {
                i++
                break
            }
            i += 1 + l
        }
        if (i + 1 >= q.size) return -1
        return ((q[i].toInt() and 0xFF) shl 8) or (q[i + 1].toInt() and 0xFF)
    }

    fun nxdomain(q: ByteArray): ByteArray = reply(q, 0x83)

    fun servfail(q: ByteArray): ByteArray = reply(q, 0x82)

    private fun reply(q: ByteArray, flagsLow: Int): ByteArray {
        val r = q.copyOf()
        val rd = r[2].toInt() and 0x01
        r[2] = (0x80 or rd).toByte()
        r[3] = flagsLow.toByte()
        return r
    }
}
