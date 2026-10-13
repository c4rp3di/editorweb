package com.vigia.app.core

enum class Estado { INACTIVA, ACTIVA, PAUSADA }

data class SesionUi(
    val cargado: Boolean = false,
    val estado: Estado = Estado.INACTIVA,
    val sesionId: Long? = null,
    val inicio: Long? = null,
    val ultimaInicio: Long? = null,
    val ultimaFin: Long? = null,
    val error: String? = null
)
