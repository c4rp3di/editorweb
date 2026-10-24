package com.perplexus.game.levels

import com.perplexus.game.math.MathUtils
import com.perplexus.game.math.Vec3
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generador procedural de pistas (plan §10.2): hélice, espiral, zigzag, bifurcación y conector.
 * Las funciones devuelven listas de puntos que se insertan en un nivel como "polyline".
 * También genera niveles aleatorios completos (modo "Infinito"), siempre validados.
 */
object LevelGenerator {

    /** Hélice alrededor del eje [axis] (0=X, 1=Y, 2=Z) centrada en [center]. */
    fun helix(
        center: Vec3, axis: Int, radius: Float, length: Float, turns: Float,
        pointsPerTurn: Int = 24, startAngle: Float = 0f, ccw: Boolean = true, startFromEnd: Boolean = false
    ): List<Vec3> {
        val n = maxOf(2, (turns * pointsPerTurn).toInt())
        val out = ArrayList<Vec3>(n + 1)
        val dir = if (ccw) 1f else -1f
        for (i in 0..n) {
            val t = i.toFloat() / n.toFloat()
            val u = (if (startFromEnd) 0.5f - length * t / length else -0.5f + t) * length
            val th = startAngle + dir * MathUtils.TWO_PI_F * turns * t
            val a = radius * cos(th); val b = radius * sin(th)
            out.add(place(center, axis, u, a, b))
        }
        return out
    }

    private fun place(c: Vec3, axis: Int, u: Float, a: Float, b: Float): Vec3 = when (axis) {
        0 -> Vec3(c.x + u, c.y + a, c.z + b)
        1 -> Vec3(c.x + b, c.y + u, c.z + a)
        else -> Vec3(c.x + a, c.y + b, c.z + u)
    }

    /** Espiral plana (en el plano perpendicular a [axis]) de radio rStart→rEnd, desplazada [offset] sobre el eje. */
    fun spiral(
        center: Vec3, axis: Int, rStart: Float, rEnd: Float, turns: Float, offset: Float,
        pointsPerTurn: Int = 28, startAngle: Float = 0f, ccw: Boolean = true
    ): List<Vec3> {
        val n = maxOf(2, (turns * pointsPerTurn).toInt())
        val dir = if (ccw) 1f else -1f
        val out = ArrayList<Vec3>(n + 1)
        for (i in 0..n) {
            val t = i.toFloat() / n.toFloat()
            val r = MathUtils.lerp(rStart, rEnd, t)
            val th = startAngle + dir * MathUtils.TWO_PI_F * turns * t
            out.add(place(center, axis, offset, r * cos(th), r * sin(th)))
        }
        return out
    }

    /** Zigzag entre dos puntos con amplitud lateral [amp] a lo largo de [side] (unitario). */
    fun zigzag(p0: Vec3, p1: Vec3, side: Vec3, amp: Float, zigs: Int): List<Vec3> {
        val out = ArrayList<Vec3>()
        out.add(p0.copy())
        for (i in 1..zigs) {
            val t = (i - 0.5f) / zigs.toFloat()
            val s = if (i % 2 == 1) 1f else -1f
            out.add(Vec3.lerp(p0, p1, t).add(side.times(amp * s)))
        }
        out.add(p1.copy())
        return out
    }

    /** Conector recto subdividido en [steps] tramos. */
    fun connector(a: Vec3, b: Vec3, steps: Int = 2): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (i in 0..steps) out.add(Vec3.lerp(a, b, i.toFloat() / steps.toFloat()))
        return out
    }

    fun pointsToJson(pts: List<Vec3>): List<Any?> = pts.map { listOf(r4(it.x), r4(it.y), r4(it.z)) }
    private fun r4(v: Float): Double = Math.round(v * 10000.0) / 10000.0

    // ------------------------------------------------------------------ nivel aleatorio
    /**
     * Pista serpenteante en capas: cada capa es un zigzag horizontal y las capas se unen por tramos
     * verticales alternando extremos. La dificultad añade capas, curvas y ranuras (huecos por los que cae la bola).
     */
    fun generate(seed: Long, difficulty: Int): Level {
        var s = seed
        for (attempt in 0 until 40) {
            val lvl = tryGenerate(s, difficulty)
            if (lvl != null) return lvl
            s += 7919L
        }
        return fallback(difficulty)
    }

    private fun tryGenerate(seed: Long, difficulty: Int): Level? {
        val rnd = Random(seed)
        val d = MathUtils.clamp(difficulty, 1, 10)
        val layers = MathUtils.clamp(2 + d / 3, 2, 4)
        val dy = 0.9f / layers.toFloat()
        if (dy < 0.2f) return null
        val zigs = 3 + d / 2
        val pts = ArrayList<Vec3>()
        val openArr = ArrayList<Double>()
        var leftToRight = true
        for (k in 0 until layers) {
            val y = -0.45f + dy * (k + 0.5f) - (dy * layers - 0.9f) / 2f
            val x0 = if (leftToRight) -0.55f else 0.55f
            val x1 = -x0
            val amp = 0.12f + rnd.nextFloat() * 0.18f
            val z0 = (rnd.nextFloat() - 0.5f) * 0.2f
            val layer = zigzag(Vec3(x0, y, z0), Vec3(x1, y, z0), Vec3(0f, 0f, 1f), amp, zigs)
            for ((i, p) in layer.withIndex()) {
                if (k > 0 && i == 0) {
                    // el primer punto coincide con el final del conector vertical anterior
                    if (pts[pts.size - 1].distanceTo(p) < 1e-4f) continue
                    pts.add(p); openArr.add(0.0); continue
                }
                pts.add(p)
                openArr.add(0.0)
            }
            leftToRight = !leftToRight
        }
        // Normaliza: openArr debe tener un valor por segmento (n-1)
        val nSeg = pts.size - 1
        val open = DoubleArray(nSeg)
        val prob = 0.04 + 0.025 * d
        var i = 3
        while (i < nSeg - 3) {
            if (rnd.nextDouble() < prob) {
                val w = 0.55 + rnd.nextDouble() * 0.5
                val len = 1 + rnd.nextInt(2)
                for (j in 0 until len) if (i + j < nSeg - 3) open[i + j] = w
                i += len + 2
            } else i++
        }
        val shell = mapOf("radius" to 1.0, "center" to mapOf("x" to 0, "y" to 0, "z" to 0))
        val level = mapOf(
            "id" to "aleatorio_$seed",
            "name" to "Aleatorio $seed",
            "description" to "Nivel generado proceduralmente (dificultad $d).",
            "order" to 1000,
            "start" to listOf(pts[0].x.toDouble(), pts[0].y.toDouble(), pts[0].z.toDouble()),
            "goal" to listOf(pts[pts.size - 1].x.toDouble(), pts[pts.size - 1].y.toDouble(), pts[pts.size - 1].z.toDouble()),
            "goalRadius" to 0.06,
            "shell" to shell,
            "ball" to mapOf("radius" to 0.04, "mass" to 0.01),
            "gravityScale" to 1.0,
            "parTime" to (40.0 + 15.0 * d),
            "track" to listOf(
                mapOf(
                    "type" to "polyline", "points" to pointsToJson(pts), "radius" to 0.07,
                    "open" to open.toList(), "material" to "metal", "capStart" to true, "capEnd" to true
                )
            )
        )
        val parsed = try { LevelFormat.fromMap(level) } catch (e: Exception) { return null }
        return if (LevelValidator.validate(parsed).ok) parsed else null
    }

    /** Nivel mínimo garantizado: hélice cerrada a lo largo de X. */
    private fun fallback(difficulty: Int): Level {
        val pts = helix(Vec3(), 0, 0.45f, 1.1f, 2.5f)
        val m = mapOf(
            "id" to "aleatorio_fallback", "name" to "Hélice", "order" to 1000,
            "start" to listOf(pts[0].x.toDouble(), pts[0].y.toDouble(), pts[0].z.toDouble()),
            "goal" to listOf(pts.last().x.toDouble(), pts.last().y.toDouble(), pts.last().z.toDouble()),
            "goalRadius" to 0.06, "parTime" to 60.0 + difficulty,
            "track" to listOf(mapOf("type" to "polyline", "points" to pointsToJson(pts), "radius" to 0.07, "capStart" to true, "capEnd" to true))
        )
        return LevelFormat.fromMap(m)
    }
}
