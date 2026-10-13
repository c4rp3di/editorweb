package com.vigia.app.core

enum class Estado { INACTIVA, ACTIVA, PAUSADA }

data class SesionUi(
    val cargado: Boolean = false,
    val estado: Estado = Estado.INACTIVA,
    val sesionId: Long? = null,
    val inicio: Long? = null,
    val vpn: Boolean = false,
    val ultimaInicio: Long? = null,
    val ultimaFin: Long? = null,
    val error: String? = null
)

data class Contadores(
    val consultas: Int = 0,
    val dominios: Int = 0,
    val apps: Int = 0
)
