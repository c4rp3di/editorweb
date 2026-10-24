package com.perplexus.game.math

/** Lista de enteros growable sin boxing. */
class IntList(initial: Int = 16) {
    @JvmField var data: IntArray = IntArray(if (initial < 4) 4 else initial)
    @JvmField var size: Int = 0

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    operator fun get(i: Int): Int = data[i]
    fun clear() { size = 0 }
    fun isEmpty(): Boolean = size == 0
    fun contains(v: Int): Boolean { for (i in 0 until size) if (data[i] == v) return true; return false }
}

/**
 * Jerarquía de volúmenes envolventes estática (top-down, división por mediana de
 * centroides sobre el eje más largo). Las hojas guardan índices de las cajas
 * originales. Pensado para el broadphase del mundo estático.
 */
class BVH(private val leafSize: Int = 2) {
    private var bounds = FloatArray(0)     // 6 floats por nodo: minXYZ, maxXYZ
    private var left = IntArray(0)
    private var right = IntArray(0)
    private var first = IntArray(0)        // inicio en indices (hojas)
    private var count = IntArray(0)        // >0 => hoja
    private var indices = IntArray(0)
    private var centroids = FloatArray(0)  // 3 floats por primitiva
    private var boxes: List<AABB> = emptyList()
    private val stack = IntArray(128)

    var nodeCount: Int = 0
        private set
    var primitiveCount: Int = 0
        private set

    fun build(primitives: List<AABB>) {
        boxes = primitives
        primitiveCount = primitives.size
        nodeCount = 0
        if (primitiveCount == 0) {
            bounds = FloatArray(0); left = IntArray(0); right = IntArray(0)
            first = IntArray(0); count = IntArray(0); indices = IntArray(0)
            return
        }
        val maxNodes = 2 * primitiveCount + 1
        bounds = FloatArray(maxNodes * 6)
        left = IntArray(maxNodes)
        right = IntArray(maxNodes)
        first = IntArray(maxNodes)
        count = IntArray(maxNodes)
        indices = IntArray(primitiveCount) { it }
        centroids = FloatArray(primitiveCount * 3)
        for (i in 0 until primitiveCount) {
            val b = primitives[i]
            centroids[i * 3] = b.centerX(); centroids[i * 3 + 1] = b.centerY(); centroids[i * 3 + 2] = b.centerZ()
        }
        buildNode(0, primitiveCount, 0)
    }

    private fun buildNode(start: Int, end: Int, depth: Int): Int {
        val node = nodeCount++
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in start until end) {
            val b = boxes[indices[i]]
            if (b.minX < x0) x0 = b.minX; if (b.minY < y0) y0 = b.minY; if (b.minZ < z0) z0 = b.minZ
            if (b.maxX > x1) x1 = b.maxX; if (b.maxY > y1) y1 = b.maxY; if (b.maxZ > z1) z1 = b.maxZ
        }
        val o = node * 6
        bounds[o] = x0; bounds[o + 1] = y0; bounds[o + 2] = z0
        bounds[o + 3] = x1; bounds[o + 4] = y1; bounds[o + 5] = z1

        val n = end - start
        if (n <= leafSize || depth > 60) {
            first[node] = start; count[node] = n
            left[node] = -1; right[node] = -1
            return node
        }
        // eje de mayor extensión de los centroides
        var cx0 = Float.MAX_VALUE; var cy0 = Float.MAX_VALUE; var cz0 = Float.MAX_VALUE
        var cx1 = -Float.MAX_VALUE; var cy1 = -Float.MAX_VALUE; var cz1 = -Float.MAX_VALUE
        for (i in start until end) {
            val k = indices[i] * 3
            val cx = centroids[k]; val cy = centroids[k + 1]; val cz = centroids[k + 2]
            if (cx < cx0) cx0 = cx; if (cx > cx1) cx1 = cx
            if (cy < cy0) cy0 = cy; if (cy > cy1) cy1 = cy
            if (cz < cz0) cz0 = cz; if (cz > cz1) cz1 = cz
        }
        val ex = cx1 - cx0; val ey = cy1 - cy0; val ez = cz1 - cz0
        val axis = if (ex >= ey && ex >= ez) 0 else if (ey >= ez) 1 else 2
        val sorted = (start until end).map { indices[it] }.sortedBy { centroids[it * 3 + axis] }
        for (i in 0 until n) indices[start + i] = sorted[i]
        val mid = start + n / 2
        count[node] = 0
        left[node] = buildNode(start, mid, depth + 1)
        right[node] = buildNode(mid, end, depth + 1)
        return node
    }

    /** Añade a out los índices de las primitivas cuyo AABB intersecta con box. */
    fun query(box: AABB, out: IntList) {
        if (nodeCount == 0) return
        var sp = 0
        stack[sp++] = 0
        while (sp > 0) {
            val node = stack[--sp]
            val o = node * 6
            if (bounds[o] > box.maxX || bounds[o + 3] < box.minX ||
                bounds[o + 1] > box.maxY || bounds[o + 4] < box.minY ||
                bounds[o + 2] > box.maxZ || bounds[o + 5] < box.minZ) continue
            val c = count[node]
            if (c > 0) {
                val f = first[node]
                for (i in f until f + c) {
                    val idx = indices[i]
                    if (boxes[idx].intersects(box)) out.add(idx)
                }
            } else if (sp < stack.size - 2) {
                stack[sp++] = left[node]
                stack[sp++] = right[node]
            }
        }
    }

    /** Distancia mínima (al cuadrado) del punto a los AABB de las hojas; útil para métricas. */
    fun depth(): Int = if (nodeCount == 0) 0 else depthOf(0)
    private fun depthOf(node: Int): Int =
        if (count[node] > 0) 1 else 1 + maxOf(depthOf(left[node]), depthOf(right[node]))

    fun rootBounds(out: AABB): AABB {
        if (nodeCount == 0) return out.reset()
        return out.set(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5])
    }
}
