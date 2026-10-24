package com.perplexus.game.levels

import com.perplexus.game.math.MathUtils
import com.perplexus.game.math.Quat
import com.perplexus.game.math.Vec3
import com.perplexus.game.physics.Box
import com.perplexus.game.physics.Capsule
import com.perplexus.game.physics.Material
import com.perplexus.game.physics.PhysicsConstants
import com.perplexus.game.physics.Shape
import com.perplexus.game.physics.Sphere
import com.perplexus.game.physics.Triangle
import com.perplexus.game.physics.Tube
import com.perplexus.game.physics.TubeJoint
import kotlin.math.acos

/**
 * Esquema JSON de los niveles (v1):
 *
 * {
 *   "id": "nivel_01", "name": "…", "description": "…", "order": 1,
 *   "start": {"x":0,"y":0,"z":0}, "goal": {…}, "goalRadius": 0.06,
 *   "shell": {"radius": 1.0, "center": {…}},
 *   "ball": {"radius": 0.04, "mass": 0.01},
 *   "gravityScale": 1.0, "parTime": 60,
 *   "track": [
 *     {"type":"polyline","points":[[x,y,z],…],"radius":0.07,"open":0.0 | [por segmento],
 *      "material":"metal","capStart":true,"capEnd":true,"branches":[{"points":[…]},…]},
 *     {"type":"tube","a":{…},"b":{…},"radius":0.07,"open":0,"capA":false,"capB":false},
 *     {"type":"capsule","a":…,"b":…,"radius":0.03,"material":"wood"},
 *     {"type":"triangle","v0":…,"v1":…,"v2":…,"material":"rubber"},
 *     {"type":"sphere","center":…,"radius":0.05},
 *     {"type":"box","center":…,"half":{…},"rotation":{"axis":{…},"angleDeg":30}}
 *   ]
 * }
 */
object LevelFormat {
    class FormatException(msg: String) : Exception(msg)

    /** Ángulo mínimo de giro (grados) a partir del cual se genera un codo hueco. */
    const val JOINT_MIN_ANGLE_DEG = 4f

    @Suppress("UNCHECKED_CAST")
    fun vec(v: Any?, what: String): Vec3 = when (v) {
        is Map<*, *> -> {
            val m = v as Map<String, Any?>
            Vec3(Json.num(m, "x", 0f), Json.num(m, "y", 0f), Json.num(m, "z", 0f))
        }
        is List<*> -> {
            if (v.size < 3) throw FormatException("'$what' necesita 3 componentes")
            Vec3((v[0] as Number).toFloat(), (v[1] as Number).toFloat(), (v[2] as Number).toFloat())
        }
        else -> throw FormatException("Falta o es inválido el vector '$what'")
    }

    fun parse(text: String): Level {
        val root = try { Json.parseObject(text) } catch (e: Json.JsonException) { throw FormatException("JSON inválido: ${e.message}") }
        return fromMap(root)
    }

    @Suppress("UNCHECKED_CAST")
    fun fromMap(root: Map<String, Any?>): Level {
        val id = Json.str(root, "id", "nivel")
        val name = Json.str(root, "name", id)
        val desc = Json.str(root, "description", "")
        val order = Json.num(root, "order", 0f).toInt()
        val start = vec(root["start"], "start")
        val goal = vec(root["goal"], "goal")
        val goalRadius = Json.num(root, "goalRadius", 0.06f)
        val shellObj = Json.obj(root, "shell")
        val shellRadius = if (shellObj != null) Json.num(shellObj, "radius", 1f) else 1f
        val shellCenter = if (shellObj != null && shellObj["center"] != null) vec(shellObj["center"], "shell.center") else Vec3()
        val ballObj = Json.obj(root, "ball")
        val ballRadius = if (ballObj != null) Json.num(ballObj, "radius", PhysicsConstants.BALL_RADIUS) else PhysicsConstants.BALL_RADIUS
        val ballMass = if (ballObj != null) Json.num(ballObj, "mass", PhysicsConstants.BALL_MASS) else PhysicsConstants.BALL_MASS
        val gScale = Json.num(root, "gravityScale", 1f)
        val par = Json.num(root, "parTime", 60f)

        val shapes = ArrayList<Shape>()
        val chain = IntArray(1)
        val track = Json.arr(root, "track") ?: throw FormatException("El nivel no tiene 'track'")
        for ((idx, item) in track.withIndex()) {
            val m = item as? Map<String, Any?> ?: throw FormatException("track[$idx] no es un objeto")
            addElement(m, shellCenter, shapes, chain, idx)
        }
        return Level(id, name, desc, order, start, goal, goalRadius, shellCenter, shellRadius, ballRadius, ballMass, gScale, par, shapes)
    }

    private fun materialOf(m: Map<String, Any?>): Material {
        val n = Json.str(m, "material", "metal")
        return Material.byName(n)
    }

    private fun addElement(m: Map<String, Any?>, shellCenter: Vec3, out: MutableList<Shape>, chain: IntArray, idx: Int) {
        when (val type = Json.str(m, "type", "")) {
            "polyline" -> addPolyline(m, shellCenter, out, chain)
            "tube" -> {
                val t = Tube(
                    vec(m["a"], "a"), vec(m["b"], "b"), Json.num(m, "radius", 0.07f),
                    MathUtils.toRad(Json.num(m, "openDeg", 0f)) + Json.num(m, "open", 0f),
                    Json.bool(m, "capA", false), Json.bool(m, "capB", false), shellCenter, materialOf(m)
                )
                t.chainId = chain[0]++
                t.chainIndex = 0
                out.add(t)
            }
            "capsule" -> out.add(Capsule(vec(m["a"], "a"), vec(m["b"], "b"), Json.num(m, "radius", 0.03f), materialOf(m)))
            "triangle" -> out.add(Triangle(vec(m["v0"], "v0"), vec(m["v1"], "v1"), vec(m["v2"], "v2"), materialOf(m)))
            "sphere" -> out.add(Sphere(vec(m["center"], "center"), Json.num(m, "radius", 0.05f), materialOf(m)))
            "box" -> {
                var q = Quat()
                val rot = Json.obj(m, "rotation")
                if (rot != null) {
                    val ax = vec(rot["axis"], "rotation.axis")
                    q = Quat.fromAxisAngle(ax.x, ax.y, ax.z, MathUtils.toRad(Json.num(rot, "angleDeg", 0f)))
                }
                out.add(Box(vec(m["center"], "center"), vec(m["half"], "half"), q, materialOf(m)))
            }
            else -> throw FormatException("track[$idx]: tipo desconocido '$type'")
        }
    }

    private fun openAt(open: Any?, i: Int): Float = when (open) {
        is Number -> open.toFloat()
        is List<*> -> if (i < open.size) ((open[i] as? Number)?.toFloat() ?: 0f) else 0f
        else -> 0f
    }

    @Suppress("UNCHECKED_CAST")
    private fun addPolyline(m: Map<String, Any?>, shellCenter: Vec3, out: MutableList<Shape>, chain: IntArray) {
        val ptsRaw = Json.arr(m, "points") ?: throw FormatException("polyline sin 'points'")
        val pts = ptsRaw.mapIndexed { i, p -> vec(p, "points[$i]") }
        if (pts.size < 2) throw FormatException("polyline necesita al menos 2 puntos")
        val radius = Json.num(m, "radius", 0.07f)
        val open = m["open"]
        val mat = materialOf(m)
        val capStart = Json.bool(m, "capStart", false)
        val capEnd = Json.bool(m, "capEnd", false)
        val branches = Json.arr(m, "branches")
        val hasBranches = branches != null && branches.isNotEmpty()
        buildChain(pts, radius, open, mat, capStart, capEnd && !hasBranches, shellCenter, out, chain)

        if (hasBranches) {
            val j = pts[pts.size - 1]
            var k = pts.size - 2
            while (k > 0 && pts[k].distanceTo(j) < 1e-5f) k--
            val dirIn = j.minus(pts[k]).normalized()
            val dirsOut = ArrayList<Vec3>()
            for ((bi, b) in branches!!.withIndex()) {
                val bm = b as? Map<String, Any?> ?: throw FormatException("branches[$bi] no es un objeto")
                val braw = Json.arr(bm, "points") ?: throw FormatException("branch sin 'points'")
                var bp = braw.mapIndexed { i, p -> vec(p, "branch.points[$i]") }
                if (bp.isEmpty() || bp[0].distanceTo(j) > 1e-4f) bp = listOf(j.copy()) + bp
                if (bp.size < 2) throw FormatException("branch necesita al menos un punto además de la unión")
                val br = Json.num(bm, "radius", radius)
                val bo = if (bm.containsKey("open")) bm["open"] else open
                val bmat = if (bm.containsKey("material")) materialOf(bm) else mat
                buildChain(bp, br, bo, bmat, false, Json.bool(bm, "capEnd", false), shellCenter, out, chain)
                dirsOut.add(bp[1].minus(bp[0]).normalized())
            }
            out.add(TubeJoint(j.copy(), radius, dirIn, dirsOut.toTypedArray(), mat))
        }
    }

    private fun buildChain(
        pts: List<Vec3>, radius: Float, open: Any?, mat: Material,
        capStart: Boolean, capEnd: Boolean, shellCenter: Vec3, out: MutableList<Shape>, chain: IntArray
    ) {
        // Elimina puntos duplicados consecutivos
        val p = ArrayList<Vec3>()
        for (v in pts) if (p.isEmpty() || p[p.size - 1].distanceTo(v) > 1e-5f) p.add(v)
        if (p.size < 2) return
        val id = chain[0]++
        val nSeg = p.size - 1
        for (i in 0 until nSeg) {
            val t = Tube(
                p[i].copy(), p[i + 1].copy(), radius, openAt(open, i),
                capStart && i == 0, capEnd && i == nSeg - 1, shellCenter, mat
            )
            t.chainId = id
            t.chainIndex = i
            out.add(t)
        }
        for (i in 1 until nSeg) {
            val dIn = p[i].minus(p[i - 1]).normalized()
            val dOut = p[i + 1].minus(p[i]).normalized()
            val c = MathUtils.clamp(dIn.dot(dOut), -1f, 1f)
            val ang = MathUtils.toDeg(acos(c))
            if (ang > JOINT_MIN_ANGLE_DEG) out.add(TubeJoint(p[i].copy(), radius, dIn, dOut, mat))
        }
    }
}
