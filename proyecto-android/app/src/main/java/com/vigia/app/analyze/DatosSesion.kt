package com.vigia.app.analyze

import com.vigia.app.core.Modulos
import com.vigia.app.store.A11yEventEntity
import com.vigia.app.store.DnsEventEntity
import com.vigia.app.store.NetEventEntity
import com.vigia.app.store.SensorEventEntity
import com.vigia.app.store.SessionEntity
import com.vigia.app.store.SysEventEntity
import org.json.JSONObject

/** Todo lo que los motores necesitan saber de una sesión, ya cargado en memoria. */
class DatosSesion(
    val sesion: SessionEntity,
    val modulos: Modulos,
    val vpn: Boolean,
    val inicio: Long,
    val fin: Long,
    val dns: List<DnsEventEntity>,
    val sys: List<SysEventEntity>,
    val sensores: List<SensorEventEntity>,
    val pantalla: List<A11yEventEntity>,
    val net: List<NetEventEntity>,
    val red: Boolean,
    val primerInventario: JSONObject?,
    val ultimoInventario: JSONObject?,
    val dominiosVistos: Set<String>,
    val ajustes: AjustesAnalisis,
    val paquetePropio: String,
    val sinLanzador: (String) -> Boolean
) {
    /** Evidencias de hasta [max] eventos DNS con un prefijo («dns:123»). */
    fun evidenciasDns(lista: List<DnsEventEntity>, max: Int = 20): List<String> {
        return lista.take(max).map { "dns:" + it.id }
    }
}
