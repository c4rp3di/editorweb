package com.carpe.gestorarchivos.data

import android.net.Uri

object PortapapelesInterno {
    enum class Modo { MOVER, COPIAR }

    var modo: Modo? = null
        private set
    var uris: List<Uri> = emptyList()
        private set
    var descripcion: String = ""
        private set

    fun establecer(modo: Modo, uris: List<Uri>, descripcion: String) {
        this.modo = modo
        this.uris = uris
        this.descripcion = descripcion
    }

    fun vaciar() {
        modo = null
        uris = emptyList()
        descripcion = ""
    }

    val hayContenido: Boolean get() = uris.isNotEmpty()
}