package com.laberinto3d.juego.game.levels

import com.laberinto3d.juego.game.physics.Box
import com.laberinto3d.juego.game.physics.Vec3
import com.laberinto3d.juego.game.physics.World

/**
 * Definición inmutable de un nivel. Las coordenadas están en metros, con el centro
 * del tablero en el origen, X hacia la derecha de la pantalla y Z hacia el jugador
 * (arriba de la pantalla = -Z). El suelo tiene su cara superior en y = 0.
 */
class Level(
    val id: Int,
    val name: String,
    /** Suelo + perímetro + paredes interiores. */
    val boxes: List<Box>,
    /** Posición inicial del CENTRO de la bola. */
    val start: Vec3,
    /** Centro de la meta (y = 0, a ras de suelo). */
    val goal: Vec3,
    val goalRadius: Float,
    /** Clave de preferencias del mejor tiempo. */
    val bestTimeKey: String,
    /** Rectángulo exterior del tablero (incluye perímetro). */
    val minX: Float,
    val maxX: Float,
    val minZ: Float,
    val maxZ: Float
) {
    fun buildWorld(): World = World(
        boxes = boxes,
        floorY = 0f,
        goalPosition = goal,
        goalRadius = goalRadius,
        minX = minX, maxX = maxX, minZ = minZ, maxZ = maxZ
    )
}
