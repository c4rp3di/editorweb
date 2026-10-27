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
        if (intent != null && intent.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        startFg()
        startVpn(intent?.getStringExtra(EXTRA_PKG))
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

    private fun startVpn(target: String?) {
        NetworkTools.init(this)
        if (pfd != null) return
        val b = Builder()
            .setSession("NetLens")
            .addAddress("10.215.173.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setMtu(1500)
            .setBlocking(true)
        try {
            if (target != null && target.isNotEmpty()) b.addAllowedApplication(target)
            else b.addDisallowedApplication(packageName)
        } catch (e: Exception) {}
        val fd = b.establish() ?: run { stopVpn(); return }
        pfd = fd
        val out = FileOutputStream(fd.fileDescriptor)
        val inp = FileInputStream(fd.fileDescriptor)
        val eng = Engine(this, out, packageManager, getSystemService(ConnectivityManager::class.java))
        engine = eng

        if (Store.mitmEnabled) {
            try {
            val p = MitmProxy(protector = { s -> protect(s) })
                proxy = p; p.start()
            } catch (e: Exception) {
                Store.mitmEnabled = false
            }
        }

        Store.running = true
        reader = Thread({
            val buf = ByteArray(32767)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = inp.read(buf)
                    if (n > 0) eng.handle(buf, n)
                    else if (n < 0) break
                    else Thread.sleep(5)
                }
            } catch (e: Exception) {}
        }, "netlens-reader").apply { start() }
    }

    private fun stopVpn() {
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
