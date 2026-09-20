package com.carpe.camara.data

enum class LenteFisica(val etiqueta: String) {
    PRINCIPAL("1x"),
    TELEOBJETIVO("2x"),
    ULTRA_GRAN_ANGULAR("0.6x")
}

enum class ModoCaptura {
    AUTO,
    PRO,
    LARGA_EXPOSICION
}

enum class Temporizador(val segundos: Int, val etiqueta: String) {
    OFF(0, "Off"),
    S2(2, "2s"),
    S5(5, "5s"),
    S10(10, "10s")
}

data class CamaraEstado(
    val lente: LenteFisica = LenteFisica.PRINCIPAL,
    val modo: ModoCaptura = ModoCaptura.AUTO,

    val iso: Int = 400,
    val isoManual: Boolean = false,

    val exposicionNs: Long = 16_666_666L,
    val exposicionManual: Boolean = false,

    val distanciaFocoDioptras: Float = 0f,
    val focoManual: Boolean = false,

    val temperaturaK: Int = 5000,
    val wbManual: Boolean = false,

    val flashAuto: Boolean = true,

    val temporizador: Temporizador = Temporizador.OFF,
    val mostrarGrid: Boolean = false,

    val exposicionLargaNs: Long = 1_000_000_000L
)