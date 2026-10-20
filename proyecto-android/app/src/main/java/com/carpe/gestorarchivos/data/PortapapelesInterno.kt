package com.carpe.gestorarchivos.data

import java.io.File

object PortapapelesInterno {
    enum class Modo { MOVER, COPIAR }

    var modo: Modo? = null
        private set
    var archivos: List<File> = emptyList()
        private set
    var descripcion: String = ""
        private set

    fun establecer(modo: Modo, archivos: List<File>, descripcion: String) {
        this.modo = modo
        this.archivos = archivos
        this.descripcion = descripcion
    }

    fun vaciar() {
        modo = null
        archivos = emptyList()
        descripcion = ""
    }

    val hayContenido: Boolean get() = archivos.isNotEmpty()
}