package com.vigia.core.red

import com.vigia.core.ui.NotificacionSesion
import kotlinx.coroutines.flow.MutableStateFlow

/** DNS: solo la consulta DNS entra en el túnel (ligero). CONEXIONES: todo el tráfico IPv4 pasa por el reenviador. */
enum class ModoVpn { DNS, CONEXIONES }

/**
 * Gancho opcional para redirigir ciertas conexiones TCP a un destino local (p. ej. un proxy). El núcleo no sabe
 * qué hay detrás: así :vigia-core no depende de BouncyCastle ni de ninguna lógica TLS.
 */
interface DesvioTcp {
    /**
     * Se llama antes de conectar cada TCP saliente. Devuelve la dirección a la que conectar en su lugar, o null para
     * conectar al destino real. [app] es «desconocida» si no se pudo resolver (Android < 10). [puertoLocal] es el
     * puerto local del socket saliente (ya enlazado): el proxy lo usa para reconocer la conexión.
     */
    fun desviar(uid: Int, app: String, ip: String, puerto: Int, puertoLocal: Int): java.net.InetSocketAddress?
}

/** Qué contesta el túnel a un dominio bloqueado. */
enum class RespuestaBloqueo { NXDOMAIN, CERO }

/**
 * Todo lo que una app decide sobre su túnel. La rellena la subclase de [NucleoVpnService] en `crearConfig()`.
 *
 * @param dnsUpstream «sistema» (usa los DNS de la red activa) o la IP de un servidor concreto, por ejemplo 1.1.1.1.
 * @param grabando se consulta en cada evento: devolver falso pone en pausa el detalle (no el bloqueo).
 * @param reiniciarSiMuere si es verdadero, Android relanza el servicio tras matarlo (bloqueador 24/7).
 * @param excluirApps paquetes que NO pasan por el túnel (en ambos modos).
 * @param bloquearDot rechaza DNS sobre TLS directo (TCP/UDP 853) para forzar el DNS del sistema. Solo modo CONEXIONES.
 * @param bloquearEch contesta vacío a las consultas DNS HTTPS(65)/SVCB(64): sin ellas el navegador no recibe la
 *   configuración ECH y el nombre de servidor (SNI) queda visible. Funciona en ambos modos.
 * @param neutralizarIpv6 enruta IPv6 al túnel y lo descarta, para que las apps dual-stack no se salten el filtro por
 *   IPv6. Solo modo CONEXIONES. Los destinos que solo existan en IPv6 quedan inaccesibles mientras la VPN esté activa.
 */
class ConfigVpn(
    val nombreSesion: String,
    val notificacion: NotificacionSesion.Datos,
    val modo: ModoVpn = ModoVpn.DNS,
    val dnsUpstream: String = "sistema",
    val bloquearQuic: Boolean = false,
    val filtro: FiltroTrafico = FiltroTrafico.NINGUNO,
    val respuestaBloqueo: RespuestaBloqueo = RespuestaBloqueo.NXDOMAIN,
    val observador: ObservadorTrafico? = null,
    val grabando: () -> Boolean = { true },
    val reiniciarSiMuere: Boolean = false,
    val excluirApps: List<String> = emptyList(),
    val desvioTcp: DesvioTcp? = null,
    val bloquearDot: Boolean = false,
    val bloquearEch: Boolean = false,
    val neutralizarIpv6: Boolean = false
)

/** Estado visible para la interfaz (un único túnel por app). */
object VpnEstado {
    /** ¿Está la VPN local activa ahora mismo? */
    val activa = MutableStateFlow(false)

    /** Motivo legible si el túnel no pudo arrancar o se detuvo solo. */
    val mensaje = MutableStateFlow<String?>(null)
}
