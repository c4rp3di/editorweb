package com.perplexus.game.levels

import com.perplexus.game.math.Vec3
import com.perplexus.game.physics.Tube
import kotlin.math.sqrt

class ValidationResult(@JvmField val errors: List<String>, @JvmField val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
    override fun toString(): String =
        "ValidationResult(ok=$ok, errors=${errors.size}, warnings=${warnings.size})" +
            errors.joinToString("") { "\n  ERROR: $it" } + warnings.joinToString("") { "\n  WARN: $it" }
}

/** Comprueba que un nivel es jugable (plan §10.5). Lógica pura, sin Android. */
object LevelValidator {
    private const val JOIN_TOL = 2e-3f
    private const val MAX_ERRORS_PER_CHECK = 6

    fun validate(level: Level): ValidationResult {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val r = level.ballRadius
        val tubes = level.tubes()

        if (level.shapes.isEmpty()) errors.add("El nivel no tiene geometría")
        if (level.shellRadius <= 0.1f) errors.add("Radio de esfera exterior inválido")
        if (r <= 0f || level.ballMass <= 0f) errors.add("Bola con radio o masa inválidos")

        // 1. start y goal dentro de la esfera
        if (level.start.distanceTo(level.shellCenter) + r > level.shellRadius) errors.add("La posición de inicio queda fuera de la esfera")
        if (level.goal.distanceTo(level.shellCenter) > level.shellRadius - r) errors.add("La meta queda fuera de la esfera")
        if (level.goalRadius <= 0f) errors.add("goalRadius debe ser > 0")

        // 2. los tubos deben dejar pasar la bola y no tener longitud nula
        for (t in tubes) {
            if (t.length < 1e-3f) errors.add("Tubo de longitud nula (cadena ${t.chainId})")
            if (t.radius < r * 1.15f) {
                errors.add("Tubo (cadena ${t.chainId}, #${t.chainIndex}) demasiado estrecho para la bola: radio ${t.radius} < ${r * 1.15f}")
                if (errors.size > 40) break
            }
        }

        // 3. contención en la esfera
        var cnt = 0
        for (t in tubes) {
            val limit = level.shellRadius - r * 2f
            if (t.a.distanceTo(level.shellCenter) + t.radius > limit || t.b.distanceTo(level.shellCenter) + t.radius > limit) {
                if (cnt++ < MAX_ERRORS_PER_CHECK) errors.add("Tubo (cadena ${t.chainId}, #${t.chainIndex}) demasiado cerca o fuera de la esfera")
            }
        }

        // 4. solapamiento entre tubos no conectados
        cnt = 0
        loop@ for (i in tubes.indices) {
            for (j in i + 1 until tubes.size) {
                val a = tubes[i]; val b = tubes[j]
                if (a.chainId == b.chainId && kotlin.math.abs(a.chainIndex - b.chainIndex) <= 3) continue
                if (connected(a, b)) continue
                if (!a.aabb.intersects(b.aabb)) continue
                val d = segmentDistance(a.a, a.b, b.a, b.b)
                if (d < a.radius + b.radius - 0.002f) {
                    errors.add("Tubos solapados (cadena ${a.chainId}#${a.chainIndex} y ${b.chainId}#${b.chainIndex}): distancia ${"%.3f".format(d)} < ${"%.3f".format(a.radius + b.radius)}")
                    if (++cnt >= MAX_ERRORS_PER_CHECK) break@loop
                }
            }
        }

        // 5. start y goal sobre/dentro de una pista
        val startTubes = tubes.indices.filter { inside(tubes[it], level.start, r) }
        val goalTubes = tubes.indices.filter { inside(tubes[it], level.goal, 0f) }
        if (startTubes.isEmpty()) {
            errors.add("El inicio no está dentro de ningún tubo (la bola caería al vacío)")
        }
        if (goalTubes.isEmpty()) {
            errors.add("La meta no está dentro de ningún tubo")
        }

        // 6. accesibilidad (BFS sobre el grafo de tubos)
        if (startTubes.isNotEmpty() && goalTubes.isNotEmpty()) {
            val n = tubes.size
            val adj = Array(n) { ArrayList<Int>() }
            for (i in 0 until n) for (j in i + 1 until n) {
                if (connected(tubes[i], tubes[j]) || (tubes[i].chainId == tubes[j].chainId && kotlin.math.abs(tubes[i].chainIndex - tubes[j].chainIndex) == 1)) {
                    adj[i].add(j); adj[j].add(i)
                }
            }
            val seen = BooleanArray(n)
            val queue = ArrayDeque<Int>()
            for (s in startTubes) { seen[s] = true; queue.addLast(s) }
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                for (v in adj[u]) if (!seen[v]) { seen[v] = true; queue.addLast(v) }
            }
            if (goalTubes.none { seen[it] }) errors.add("La meta no es accesible desde el inicio")
        }

        // 7. avisos
        if (level.parTimeSec <= 0f) warnings.add("parTime no positivo")
        val deadEnds = tubes.count { (it.capA && !hasNeighborAtA(it, tubes)) || (it.capB && !hasNeighborAtB(it, tubes)) }
        if (deadEnds > 0) warnings.add("$deadEnds extremos cerrados (posibles callejones sin salida)")
        val maxOpen = tubes.maxOfOrNull { it.openHalfAngle } ?: 0f
        if (maxOpen > 2.2f) warnings.add("Ranuras muy anchas (${"%.2f".format(maxOpen)} rad): la bola caerá con facilidad")

        return ValidationResult(errors, warnings)
    }

    private fun hasNeighborAtA(t: Tube, all: List<Tube>): Boolean = all.any { it !== t && (it.a.distanceTo(t.a) < JOIN_TOL || it.b.distanceTo(t.a) < JOIN_TOL) }
    private fun hasNeighborAtB(t: Tube, all: List<Tube>): Boolean = all.any { it !== t && (it.a.distanceTo(t.b) < JOIN_TOL || it.b.distanceTo(t.b) < JOIN_TOL) }

    /** Dos tubos comparten algún extremo. */
    fun connected(a: Tube, b: Tube): Boolean =
        a.a.distanceTo(b.a) < JOIN_TOL || a.a.distanceTo(b.b) < JOIN_TOL ||
            a.b.distanceTo(b.a) < JOIN_TOL || a.b.distanceTo(b.b) < JOIN_TOL

    /** ¿El punto (con una bola de radio r) cabe dentro del tubo t? */
    fun inside(t: Tube, p: Vec3, r: Float): Boolean {
        val ax = p.x - t.a.x; val ay = p.y - t.a.y; val az = p.z - t.a.z
        val s = ax * t.axis.x + ay * t.axis.y + az * t.axis.z
        if (s < -0.002f || s > t.length + 0.002f) return false
        val rx = ax - t.axis.x * s; val ry = ay - t.axis.y * s; val rz = az - t.axis.z * s
        val rd = sqrt(rx * rx + ry * ry + rz * rz)
        return rd <= t.radius - r + 0.003f
    }

    /** Distancia mínima entre dos segmentos (Ericson, Real-Time Collision Detection §5.1.9). */
    fun segmentDistance(p1: Vec3, q1: Vec3, p2: Vec3, q2: Vec3): Float {
        val d1 = q1.minus(p1); val d2 = q2.minus(p2); val r = p1.minus(p2)
        val a = d1.dot(d1); val e = d2.dot(d2); val f = d2.dot(r)
        val eps = 1e-9f
        var s: Float; var t: Float
        if (a <= eps && e <= eps) return p1.distanceTo(p2)
        if (a <= eps) {
            s = 0f; t = clamp01(f / e)
        } else {
            val c = d1.dot(r)
            if (e <= eps) {
                t = 0f; s = clamp01(-c / a)
            } else {
                val b = d1.dot(d2)
                val denom = a * e - b * b
                s = if (denom > eps) clamp01((b * f - c * e) / denom) else 0f
                t = (b * s + f) / e
                if (t < 0f) { t = 0f; s = clamp01(-c / a) }
                else if (t > 1f) { t = 1f; s = clamp01((b - c) / a) }
            }
        }
        val c1 = p1.plus(d1.times(s)); val c2 = p2.plus(d2.times(t))
        return c1.distanceTo(c2)
    }

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
}
