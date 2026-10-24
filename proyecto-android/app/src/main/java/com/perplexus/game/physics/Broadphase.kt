package com.perplexus.game.physics

import com.perplexus.game.math.AABB
import com.perplexus.game.math.BVH
import com.perplexus.game.math.IntList
import com.perplexus.game.math.Vec3

/** Broadphase sobre un BVH estático de las primitivas del nivel. */
class Broadphase {
    private val bvh = BVH(2)
    private var shapes: List<Shape> = emptyList()
    private val box = AABB()

    val nodeCount: Int get() = bvh.nodeCount
    val depth: Int get() = bvh.depth()

    fun build(list: List<Shape>) {
        shapes = list
        for (i in list.indices) list[i].id = i
        bvh.build(list.map { it.aabb })
    }

    /**
     * Devuelve en out los índices de las primitivas que pueden tocar a la bola
     * (esfera de radio r en p, con desplazamiento previsto d en el paso).
     */
    fun query(p: Vec3, r: Float, d: Vec3, margin: Float, out: IntList) {
        out.clear()
        val e = r + margin
        box.set(p.x - e, p.y - e, p.z - e, p.x + e, p.y + e, p.z + e)
        box.expandPoint(p.x + d.x - e, p.y + d.y - e, p.z + d.z - e)
        box.expandPoint(p.x + d.x + e, p.y + d.y + e, p.z + d.z + e)
        bvh.query(box, out)
    }

    fun shape(i: Int): Shape = shapes[i]
    val size: Int get() = shapes.size
}
