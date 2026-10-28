package com.lab.netlens

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream

class NetLensVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.lab.netlens.START"
        const val ACTION_STOP = "com.lab.netlens.STOP"
        const val EXTRA_PKG = "pkg"
        private const val CHANNEL = "netlens"
    }

    private var pfd: ParcelFileDescriptor? = null
    private var engine: Engine? = null
    private var proxy: MitmProxy? = null
    private var reader: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) { DebugLog.log("VPN", "Solicitud de parada recibida"); stopVpn(); return START_NOT_STICKY }
        val target = intent?.getStringExtra(EXTRA_PKG)
        DebugLog.log("VPN", "Solicitud de inicio; alcance=${target ?: "todas las apps"}; MITM=${Store.mitmEnabled}; bloquearQUIC=${Store.blockQuic}; bloquearECH=${Store.rejectEch}; antiDoH=${Store.blockDoh}; neutralizarIPv6=${Store.blockIpv6}; reglas=${NetworkTools.enabled}; DNS=${NetworkTools.dnsMode}")
        startFg()
        startVpn(target)
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Captura de red", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, NetLensVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NetLens está observando el tráfico de este móvil")
            .setContentText("Todo se analiza en local. Toca para abrir.")
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    // 2.3.2 · Mismo criterio que Engine.protect(): se enlaza el socket para
    // garantizar el descriptor nativo, se reintenta y, si Android sigue sin
    // marcarlo, NO se aborta la conexión MITM: el ámbito de la VPN
    // (addAllowedApplication en modo MITM, verificado al iniciar) ya excluye
    // al propio NetLens del TUN, así que no hay riesgo de bucle. El resultado
    // queda en el log de depuración.
    private fun protectUpstreamSocket(socket: java.net.Socket): Boolean {
        if (!socket.isBound) try { socket.bind(null) } catch (_: Exception) {}
        var ok = false
        for (i in 1..3) {
            try { ok = protect(socket); if (ok) break } catch (e: Exception) {
                DebugLog.log("MITM", "ERROR protect(socket remoto) (intento $i): ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
            }
            try { Thread.sleep(50L * i) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        DebugLog.log("MITM", "protect(socket remoto)=${if (ok) "OK" else "no aplicó; se continúa por el ámbito de apps (sin riesgo de bucle)"}")
        if (!ok) Store.lastRuntimeError = "protect(socket MITM) no aplicó; continúa por el ámbito de apps que ya excluye a NetLens"
        return true
    }

    private fun startVpn(target: String?) {
        if (pfd != null) return
        Store.lastStartError = null
        Store.lastRuntimeError = null
        DebugLog.log("VPN", "Inicializando servicio VPN")
        try {
            NetworkTools.init(this)
            SessionHistory.init(this)

            val b = Builder()
                .setSession("NetLens")
                .addAddress("10.215.173.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setMtu(1500)
                .setBlocking(true)

            // 2.4 · IPv6: sin rutas v6 en el TUN, las apps con IPv6 salen
            // FUERA de la VPN y se saltan todo el filtrado. Con
            // «Neutralizar IPv6» se enruta el espacio global (2000::/3) y el
            // ULA (fc00::/7) al TUN; el motor (solo IPv4) los descarta: las
            // apps dual-stack decaen a IPv4 y los destinos solo-v6 quedan
            // inaccesibles mientras dure la captura.
            if (Store.blockIpv6) {
                b.addAddress("fd00:15:26::1", 128)
                b.addRoute("2000::", 3)
                b.addRoute("fc00::", 7)
            }

            // App scope is security-critical: never swallow a bad package/scope
            // selection, because Builder would otherwise default to all apps.
            if (!target.isNullOrBlank()) {
                packageManager.getApplicationInfo(target, 0)
                b.addAllowedApplication(target)
            } else {
                b.addDisallowedApplication(packageName)
            }

            val fd = b.establish() ?: throw IllegalStateException(
                "Android no estableció la VPN. Comprueba el permiso VPN y vuelve a iniciar."
            )
            pfd = fd
            DebugLog.log("VPN", "Builder.establish() correcto; rutas=IPv4" + (if (Store.blockIpv6) " + IPv6 al TUN (neutralizado)" else " ; IPv6 fuera de la VPN (sin capturar)"))

            // For MITM, bind and start the listener synchronously before the TUN
            // reader starts forwarding any TCP/443 connection to 127.0.0.1:8888.
            if (Store.mitmEnabled) {
                val p = MitmProxy(protector = { s -> protectUpstreamSocket(s) })
                p.start()
                proxy = p
                DebugLog.log("MITM", "Listener iniciado en puerto ${MitmProxy.PORT}")
            }

            val out = FileOutputStream(fd.fileDescriptor)
            val inp = FileInputStream(fd.fileDescriptor)
            engine = Engine(this, out, packageManager, getSystemService(ConnectivityManager::class.java))

            Store.running = true
            SessionHistory.begin(target)
            DebugLog.log("VPN", "Captura activa; lector TUN iniciando")
            reader = Thread({
                val buf = ByteArray(32767)
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val n = inp.read(buf)
                        if (n > 0) engine?.handle(buf, n)
                        else if (n < 0) break
                        else Thread.sleep(5)
                    }
                } catch (e: Exception) {
                    if (Store.running && !Thread.currentThread().isInterrupted) {
                        Store.lastStartError = "La lectura de la VPN terminó: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
                        DebugLog.log("VPN", "ERROR lector TUN: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
                    }
                }
            }, "netlens-reader").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            if (Store.running) try { SessionHistory.finish() } catch (_: Exception) {}
            Store.lastStartError = when (e) {
                is android.content.pm.PackageManager.NameNotFoundException -> "La app seleccionada ya no está instalada; no se inició la captura."
                else -> e.message ?: (e.javaClass.simpleName)
            }
            Store.running = false
            DebugLog.log("VPN", "ERROR al iniciar: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
            try { engine?.stop() } catch (_: Exception) {}
            engine = null
            try { proxy?.stop() } catch (_: Exception) {}
            proxy = null
            try { pfd?.close() } catch (_: Exception) {}
            pfd = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopVpn() {
        DebugLog.log("VPN", "Deteniendo captura; estabaActiva=${Store.running}")
        if (Store.running) SessionHistory.finish()
        Store.running = false
        try { engine?.stop() } catch (e: Exception) {}
        engine = null
        try { proxy?.stop() } catch (e: Exception) {}
        proxy = null
        try { pfd?.close() } catch (e: Exception) {}
        pfd = null
        reader?.interrupt(); reader = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { stopVpn() }
    override fun onDestroy() { stopVpn(); super.onDestroy() }
}

