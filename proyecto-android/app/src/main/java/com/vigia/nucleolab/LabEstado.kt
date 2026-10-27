package com.vigia.nucleolab

import com.vigia.core.red.ConfigVpn
import com.vigia.core.red.ContadorBloqueos
import com.vigia.core.red.FiltroPorConjuntos
import com.vigia.core.red.ModoVpn
import com.vigia.core.red.NucleoVpnService
import com.vigia.core.red.RespuestaBloqueo
import com.vigia.core.ui.NotificacionSesion

/** Estado compartido entre la pantalla y el servicio (mismo proceso). */
object LabEstado {
    val filtro = FiltroPorConjuntos()
    val contador = ContadorBloqueos()
}

/**
 * Subclase mínima de ejemplo: así es como cada app de la suite usa el túnel. Todo el trabajo está en el núcleo;
 * la app solo decide filtro, observador y notificación.
 */
class LabVpnService : NucleoVpnService() {
    override fun crearConfig(): ConfigVpn {
        return ConfigVpn(
            nombreSesion = "Núcleo Lab",
            notificacion = NotificacionSesion.Datos(
                canalId = "lab_vpn",
                canalNombre = "Núcleo Lab",
                id = 77,
                titulo = "Núcleo Lab activo",
                texto = "Filtrando el DNS de prueba",
                actividad = MainActivity::class.java,
                textoDetener = "Detener"
            ),
            modo = ModoVpn.DNS,
            filtro = LabEstado.filtro,
            respuestaBloqueo = RespuestaBloqueo.CERO,
            observador = LabEstado.contador
        )
    }
}
