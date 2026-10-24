package com.perplexus.game.levels

import com.perplexus.game.math.Vec3
import com.perplexus.game.physics.Material
import com.perplexus.game.physics.Shape
import com.perplexus.game.physics.Shell
import com.perplexus.game.physics.Tube

/** Definición completa de un nivel ya convertida a geometría de colisión. */
class Level(
    @JvmField val id: String,
    @JvmField val name: String,
    @JvmField val description: String,
    @JvmField val order: Int,
    @JvmField val start: Vec3,
    @JvmField val goal: Vec3,
    @JvmField val goalRadius: Float,
    @JvmField val shellCenter: Vec3,
    @JvmField val shellRadius: Float,
    @JvmField val ballRadius: Float,
    @JvmField val ballMass: Float,
    @JvmField val gravityScale: Float,
    @JvmField val parTimeSec: Float,
    @JvmField val shapes: List<Shape>
) {
    @JvmField val shell: Shell = Shell(shellCenter, shellRadius, Material.METAL)

    fun tubes(): List<Tube> = shapes.filterIsInstance<Tube>()

    override fun toString(): String = "Level($id, '$name', ${shapes.size} shapes)"
}
