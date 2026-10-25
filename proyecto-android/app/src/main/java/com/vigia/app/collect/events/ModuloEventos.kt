package com.vigia.app.collect.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.vigia.app.collect.EscritorEventos
import com.vigia.app.core.Recoleccion
import org.json.JSONObject

/**
 * Módulo events: instalaciones y borrados de apps, pantalla, desbloqueo, cambios de red,
 * de hora, de idioma y del almacén de certificados. Solo metadatos: nunca contenido.
 * Se registra al empezar la sesión y se da de baja al terminar (no hay nada permanente).
 */
class ModuloEventos(private val ctx: Context, private val sink: EscritorEventos) {

    private var receptorPaquetes: BroadcastReceiver? = null
    private var receptorSistema: BroadcastReceiver? = null
    private var callbackRed: ConnectivityManager.NetworkCallback? = null
    private var ultimaClaveRed = ""

    fun iniciar() {
        val paquetes = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) alPaquete(intent)
            }
        }
        val filtroPaquetes = IntentFilter()
        filtroPaquetes.addAction(Intent.ACTION_PACKAGE_ADDED)
        filtroPaquetes.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filtroPaquetes.addAction(Intent.ACTION_PACKAGE_REPLACED)
        filtroPaquetes.addDataScheme("package")
        ContextCompat.registerReceiver(ctx, paquetes, filtroPaquetes, ContextCompat.RECEIVER_NOT_EXPORTED)
        receptorPaquetes = paquetes

        val sistema = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) alSistema(intent)
            }
        }
        val filtroSistema = IntentFilter()
        filtroSistema.addAction(Intent.ACTION_SCREEN_ON)
        filtroSistema.addAction(Intent.ACTION_SCREEN_OFF)
        filtroSistema.addAction(Intent.ACTION_USER_PRESENT)
        filtroSistema.addAction(Intent.ACTION_LOCALE_CHANGED)
        filtroSistema.addAction(Intent.ACTION_TIME_CHANGED)
        filtroSistema.addAction(Intent.ACTION_TIMEZONE_CHANGED)
        filtroSistema.addAction(ACCION_ALMACEN_CERTIFICADOS)
        ContextCompat.registerReceiver(ctx, sistema, filtroSistema, ContextCompat.RECEIVER_NOT_EXPORTED)
        receptorSistema = sistema

        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    ultimaClaveRed = ""
                    registrar("red_perdida", "{}")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val transporte = nombreTransporte(caps)
                    val validada = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    val clave = transporte + "/" + validada
                    if (clave != ultimaClaveRed) {
                        ultimaClaveRed = clave
                        registrar(
                            "red_cambio",
                            JSONObject().put("transporte", transporte).put("validada", validada).toString()
                        )
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            callbackRed = cb
        } catch (e: Exception) {
        }
    }

    fun detener() {
        val p = receptorPaquetes
        if (p != null) {
            try {
                ctx.unregisterReceiver(p)
            } catch (e: IllegalArgumentException) {
            }
        }
        val s = receptorSistema
        if (s != null) {
            try {
                ctx.unregisterReceiver(s)
            } catch (e: IllegalArgumentException) {
            }
        }
        val cb = callbackRed
        if (cb != null) {
            try {
                ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
            } catch (e: Exception) {
            }
        }
        receptorPaquetes = null
        receptorSistema = null
        callbackRed = null
    }

    private fun registrar(tipo: String, payload: String) {
        if (Recoleccion.grabando) sink.sys(tipo, payload)
    }

    private fun alPaquete(intent: Intent) {
        val paquete = intent.data?.schemeSpecificPart ?: return
        val reemplazo = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        val tipo = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> if (reemplazo) return else "paquete_instalado"
            Intent.ACTION_PACKAGE_REMOVED -> if (reemplazo) return else "paquete_eliminado"
            Intent.ACTION_PACKAGE_REPLACED -> "paquete_actualizado"
            else -> return
        }
        val payload = JSONObject()
            .put("paquete", paquete)
            .put("uid", intent.getIntExtra(Intent.EXTRA_UID, -1))
            .toString()
        registrar(tipo, payload)
    }

    private fun alSistema(intent: Intent) {
        val tipo = when (intent.action) {
            Intent.ACTION_SCREEN_ON -> "pantalla_encendida"
            Intent.ACTION_SCREEN_OFF -> "pantalla_apagada"
            Intent.ACTION_USER_PRESENT -> "desbloqueo"
            Intent.ACTION_LOCALE_CHANGED -> "idioma_cambiado"
            Intent.ACTION_TIME_CHANGED -> "hora_cambiada"
            Intent.ACTION_TIMEZONE_CHANGED -> "zona_horaria_cambiada"
            ACCION_ALMACEN_CERTIFICADOS -> "certificados_cambiaron"
            else -> return
        }
        registrar(tipo, "{}")
    }

    private fun nombreTransporte(caps: NetworkCapabilities): String {
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos_moviles"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "otro"
        }
    }

    companion object {
        /** KeyChain.ACTION_TRUST_STORE_CHANGED (Android 8+). */
        const val ACCION_ALMACEN_CERTIFICADOS = "android.security.action.TRUST_STORE_CHANGED"
    }
}
