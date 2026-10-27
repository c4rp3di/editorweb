package com.vigia.adblock

import android.content.Intent
import com.vigia.core.red.ConfigVpn
import com.vigia.core.red.ModoVpn
import com.vigia.core.red.NucleoVpnService
import com.vigia.core.red.ObservadorTrafico
import com.vigia.core.red.RespuestaBloqueo
import com.vigia.core.ui.NotificacionSesion
import com.vigia.core.util.Hilos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Solo cuenta bloqueos: el túnel no resuelve la app de origen ni lee respuestas (mínimo coste de batería). */
object ObservadorAdBlock : ObservadorTrafico {
    override val quiereDetalle: Boolean get() = false

    override fun alBloqueo(dominio: String) {
        Estadisticas.registrar(dominio)
    }
}

/**
 * El servicio del AdBlock: toda la mecánica de red está en :vigia-core. Aquí solo se decide qué filtro usar,
 * qué observador (contador en memoria, sin base de datos) y cómo se ve la notificación.
 */
class AdBlockVpnService : NucleoVpnService() {

    private val ambito = Hilos.nuevoAmbito(Dispatchers.Default)
    private var ticker: Job? = null

    override fun crearConfig(): ConfigVpn {
        val c = applicationContext
        Estadisticas.iniciar(c)
        ListasManager.asegurarCargado(c)
        Ajustes.setActiva(c, true)
        iniciarTicker()
        val profundo = Ajustes.modoProfundo(c)
        return ConfigVpn(
            nombreSesion = "AdBlock Soberano",
            notificacion = datosNotificacion(),
            modo = if (profundo) ModoVpn.CONEXIONES else ModoVpn.DNS,
            dnsUpstream = Ajustes.dnsUpstream(c),
            bloquearQuic = profundo,
            filtro = AdBlockEstado.filtro,
            respuestaBloqueo = if (Ajustes.bloqueoNxdomain(c)) RespuestaBloqueo.NXDOMAIN else RespuestaBloqueo.CERO,
            observador = ObservadorAdBlock,
            reiniciarSiMuere = true,
            excluirApps = Ajustes.excluidas(c).toList()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // «Desactivar» en la notificación es una orden del usuario: no se reactivará solo al arrancar el móvil.
        if (intent != null && NotificacionSesion.ACCION_DETENER == intent.action) {
            Ajustes.setActiva(applicationContext, false)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onRevoke() {
        // El usuario apagó la VPN desde los ajustes de Android.
        Ajustes.setActiva(applicationContext, false)
        Estadisticas.guardar(applicationContext)
        super.onRevoke()
    }

    override fun onDestroy() {
        ticker?.cancel()
        ambito.cancel()
        Estadisticas.guardar(applicationContext)
        super.onDestroy()
    }

    private fun iniciarTicker() {
        if (ticker != null) return
        ticker = ambito.launch {
            var ultimo = -1L
            while (true) {
                delay(60_000L)
                Estadisticas.guardar(applicationContext)
                val hoy = Estadisticas.hoy()
                if (hoy != ultimo) {
                    ultimo = hoy
                    actualizarNotificacion(datosNotificacion())
                }
            }
        }
    }

    private fun datosNotificacion(): NotificacionSesion.Datos {
        val hoy = Estadisticas.hoy()
        val texto = if (hoy == 0L) "Protección activa" else NumberFormat.getIntegerInstance().format(hoy) + " anuncios bloqueados hoy"
        return NotificacionSesion.Datos(
            canalId = "adblock_proteccion",
            canalNombre = "Protección AdBlock",
            id = 101,
            titulo = "AdBlock Soberano",
            texto = texto,
            actividad = MainActivity::class.java,
            textoDetener = "Desactivar"
        )
    }
}
