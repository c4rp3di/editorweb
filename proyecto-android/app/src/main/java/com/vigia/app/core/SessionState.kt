package com.vigia.app.core

enum class Estado { INACTIVA, ACTIVA, PAUSADA }

/** Módulos de recolección elegidos para una sesión (la VPN/DNS va aparte). */
data class Modulos(
    val eventos: Boolean = false,
    val inventario: Boolean = false,
    val sensores: Boolean = false,
    val a11y: Boolean = false
) {
    fun alguno(): Boolean = eventos || inventario || sensores || a11y
}

data class SesionUi(
    val cargado: Boolean = false,
    val estado: Estado = Estado.INACTIVA,
    val sesionId: Long? = null,
    val inicio: Long? = null,
    val vpn: Boolean = false,
    val dns: String = "sistema",
    val red: Boolean = false,
    val bloquearQuic: Boolean = true,
    val modulos: Modulos = Modulos(),
    val ultimaId: Long? = null,
    val ultimaInicio: Long? = null,
    val ultimaFin: Long? = null,
    val error: String? = null
)

data class Contadores(
    val consultas: Int = 0,
    val dominios: Int = 0,
    val apps: Int = 0,
    val sistema: Int = 0,
    val sensores: Int = 0,
    val pantalla: Int = 0,
    val hallazgos: Int = 0,
    val hallazgosAltos: Int = 0,
    val conexiones: Int = 0,
    val destinos: Int = 0,
    val bytes: Long = 0L
)

/** Fila de la línea de tiempo unificada (DNS + sistema + sensores + pantalla). */
data class FilaLinea(
    val ts: Long,
    val cat: String,
    val tipo: String,
    val app: String,
    val texto: String
)
