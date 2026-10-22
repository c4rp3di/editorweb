package com.laberinto3d.juego.game.audio

/** Identificadores lógicos de los efectos de sonido. */
object SoundIds {
    const val BOUNCE = 0
    const val GOAL = 1
    const val TAP = 2
    const val COUNT = 3

    val FILE_NAMES = arrayOf("sfx_bounce.wav", "sfx_goal.wav", "sfx_tap.wav")
    val LABELS = arrayOf("rebote", "meta", "toque")
}
