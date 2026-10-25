package com.vigia.app.core

/** Plantillas de auditoría: combinaciones de módulos pensadas para casos concretos. El texto de cada una está en strings.xml. */
object Plantillas {
    class Config(
        val vpn: Boolean,
        val red: Boolean,
        val quic: Boolean,
        val eventos: Boolean,
        val inventario: Boolean,
        val sensores: Boolean,
        val a11y: Boolean
    )

    /** 0 = personalizada (no cambia nada). 1 = app sospechosa, 2 = wifi pública, 3 = consumo de datos. */
    fun config(indice: Int): Config? {
        return when (indice) {
            1 -> Config(vpn = true, red = true, quic = true, eventos = true, inventario = true, sensores = true, a11y = true)
            2 -> Config(vpn = true, red = true, quic = true, eventos = true, inventario = false, sensores = false, a11y = false)
            3 -> Config(vpn = true, red = true, quic = false, eventos = false, inventario = false, sensores = false, a11y = false)
            else -> null
        }
    }
}
