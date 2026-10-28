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
        DebugLog.log("VPN", "Solicitud de inicio; alcance=${target ?: "todas las apps"}; MITM=${Store.mitmEnabled}; bloquearQUIC=${Store.blockQuic}; reglas=${NetworkTools.enabled}; DNS=${NetworkTools.dnsMode}")
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

    private fun protectUpstreamSocket(socket: java.net.Socket): Boolean {
        val ok = try { protect(socket) } catch (e: Exception) {
            Store.lastRuntimeError = "Error al excluir el socket remoto MITM: ${e.message ?: e.javaClass.simpleName}"
            DebugLog.log("MITM", "ERROR protect(socket remoto): ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}")
            throw e
        }
        DebugLog.log("MITM", "protect(socket remoto)=${if (ok) "OK" else "FALLO"}")
        if (!ok) {
            val permissionRevoked = try { VpnService.prepare(this) != null } catch (_: Exception) { true }
            Store.lastRuntimeError = if (permissionRevoked)
                "Android no pudo excluir el socket MITM: el permiso VPN no está activo o fue revocado."
            else
                "Android rechazó el socket remoto MITM aunque el permiso VPN parece activo."
        }
        return ok
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
            DebugLog.log("VPN", "Builder.establish() correcto; ruta=IPv4 por defecto; IPv6 no configurado")

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
