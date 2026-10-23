package com.laberinto3d.juego.game.levels

import com.laberinto3d.juego.game.core.Config
import com.laberinto3d.juego.game.physics.Box
import com.laberinto3d.juego.game.physics.Vec3

/**
 * Convierte un mapa de texto en un [Level]. Formato (una celda = Config.CELL metros):
 *
 *   [pisos]    altura del suelo de cada celda:  '.' o '0' = a ras (y=0),  '1'..'9' = 1..9 peldaños
 *              (Config.HEIGHT_STEP m cada uno),  '-', '_' o ' ' = vacío (agujero: la bola cae)
 *   [muros]    '#' = muro sobre esa celda (Config.WALL_HEIGHT de alto sobre el suelo de la celda)
 *   [objetos]  'S' = inicio, 'G' = meta, 'a'..'z' = checkpoints (en orden alfabético)
 *
 * Las tres secciones tienen las mismas filas y columnas (la primera fila del texto es la de ARRIBA
 * en pantalla). Se puede omitir [muros]. Las filas más cortas se completan con vacío / sin muro.
 */
object MapParser {
    class MapException(msg: String) : RuntimeException(msg)

    private val WALL_COLOR = floatArrayOf(0.45f, 0.40f, 0.86f, 1f)

    /** Cuadrícula resultante, útil también para pruebas. */
    class Grid(
        val w: Int, val h: Int,
        /** altura en peldaños por celda; -1 = agujero */
        val floor: Array<IntArray>,
        val wall: Array<BooleanArray>,
        val startCell: IntArray,       // [fila, col]
        val goalCell: IntArray,
        val checkpointCells: List<IntArray>
    )

    fun parseGrid(text: String): Grid {
        val sections = HashMap<String, ArrayList<String>>()
        var cur: ArrayList<String>? = null
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                cur = ArrayList()
                sections[t.substring(1, t.length - 1).lowercase()] = cur
                continue
            }
            if (cur != null) cur.add(line)
        }
        fun clean(l: ArrayList<String>?): List<String> {
            if (l == null) return emptyList()
            var a = 0
            var b = l.size
            // se quitan sólo las líneas completamente vacías (longitud 0) del principio y del final
            while (a < b && l[a].isEmpty()) a++
            while (b > a && l[b - 1].isEmpty()) b--
            return l.subList(a, b)
        }
        val pisos = clean(sections["pisos"])
        if (pisos.isEmpty()) throw MapException("Falta la sección [pisos]")
        val muros = clean(sections["muros"])
        val objetos = clean(sections["objetos"])
        val h = pisos.size
        val w = pisos.maxOf { it.length }
        val floor = Array(h) { r -> IntArray(w) { c ->
            val ch = pisos[r].getOrElse(c) { '-' }
            when (ch) {
                '.' -> 0
                in '0'..'9' -> ch - '0'
                '-', '_', ' ' -> -1
                else -> throw MapException("Carácter de [pisos] no válido '$ch' en fila ${r + 1}, columna ${c + 1}")
            }
        } }
        val wall = Array(h) { r -> BooleanArray(w) { c -> muros.getOrNull(r)?.getOrNull(c) == '#' } }
        var start: IntArray? = null
        var goal: IntArray? = null
        val cps = java.util.TreeMap<Char, IntArray>()
        for (r in 0 until h) for (c in 0 until w) {
            when (val ch = objetos.getOrNull(r)?.getOrNull(c) ?: '.') {
                'S' -> { if (start != null) throw MapException("Más de un inicio 'S'"); start = intArrayOf(r, c) }
                'G' -> { if (goal != null) throw MapException("Más de una meta 'G'"); goal = intArrayOf(r, c) }
                in 'a'..'z' -> { if (cps.containsKey(ch)) throw MapException("Checkpoint '$ch' repetido"); cps[ch] = intArrayOf(r, c) }
                '.', ' ' -> {}
                else -> throw MapException("Carácter de [objetos] no válido '$ch' en fila ${r + 1}, columna ${c + 1}")
            }
        }
        if (start == null) throw MapException("Falta el inicio 'S'")
        if (goal == null) throw MapException("Falta la meta 'G'")
        for (cell in listOf(start, goal) + cps.values) {
            if (floor[cell[0]][cell[1]] < 0) throw MapException("Objeto sobre un agujero en fila ${cell[0] + 1}, columna ${cell[1] + 1}")
        }
        return Grid(w, h, floor, wall, start, goal, cps.values.toList())
    }

    fun topOf(heightIdx: Int): Float = heightIdx * Config.HEIGHT_STEP

    fun build(id: Int, name: String, text: String): Level {
        val g = parseGrid(text)
        val cell = Config.CELL
        val x0 = -g.w * cell / 2f
        val z0 = -g.h * cell / 2f
        fun cx(c: Int) = x0 + (c + 0.5f) * cell
        fun cz(r: Int) = z0 + (r + 0.5f) * cell
        val boxes = ArrayList<Box>()
        var topY = Config.WALL_HEIGHT

        // Suelos: rectángulos fusionados de la misma altura
        val keysFloor = Array(g.h) { r -> IntArray(g.w) { c -> g.floor[r][c] } }
        for (m in mergeRects(keysFloor, g.w, g.h)) {
            val top = topOf(m.key)
            val l = 0.04f * m.key
            val col = floatArrayOf(0.17f + l, 0.17f + l, 0.28f + l * 1.2f, 1f)
            boxes.add(Box(
                Vec3(x0 + m.c0 * cell, Config.FLOOR_BASE_Y, z0 + m.r0 * cell),
                Vec3(x0 + (m.c1 + 1) * cell, top, z0 + (m.r1 + 1) * cell), col, true))
            if (top > topY) topY = top
        }
        // Muros: rectángulos fusionados con la misma altura de base
        val keysWall = Array(g.h) { r -> IntArray(g.w) { c -> if (g.wall[r][c]) maxOf(g.floor[r][c], 0) + (if (g.floor[r][c] < 0) 100 else 0) else -1 } }
        for (m in mergeRects(keysWall, g.w, g.h)) {
            val hole = m.key >= 100
            val idx = if (hole) m.key - 100 else m.key
            val top = topOf(idx)
            val minY = if (hole) Config.FLOOR_BASE_Y else top
            boxes.add(Box(
                Vec3(x0 + m.c0 * cell, minY, z0 + m.r0 * cell),
                Vec3(x0 + (m.c1 + 1) * cell, top + Config.WALL_HEIGHT, z0 + (m.r1 + 1) * cell), WALL_COLOR))
            if (top + Config.WALL_HEIGHT > topY) topY = top + Config.WALL_HEIGHT
        }
        val sy = topOf(g.floor[g.startCell[0]][g.startCell[1]])
        val start = Vec3(cx(g.startCell[1]), sy + Config.BALL_RADIUS, cz(g.startCell[0]))
        val goal = Vec3(cx(g.goalCell[1]), topOf(g.floor[g.goalCell[0]][g.goalCell[1]]), cz(g.goalCell[0]))
        val cps = g.checkpointCells.map { Vec3(cx(it[1]), topOf(g.floor[it[0]][it[1]]), cz(it[0])) }
        return Level(
            id = id, name = name, boxes = boxes, start = start, goal = goal, goalRadius = 0.30f,
            bestTimeKey = Levels.bestTimeKey(id),
            minX = x0, maxX = x0 + g.w * cell, minZ = z0, maxZ = z0 + g.h * cell,
            checkpoints = cps, topY = topY
        )
    }

    class Rect(val r0: Int, val c0: Int, val r1: Int, val c1: Int, val key: Int)

    /** Fusión voraz de celdas con la misma clave (>= 0) en rectángulos. Claves < 0 se ignoran. */
    fun mergeRects(keys: Array<IntArray>, w: Int, h: Int): List<Rect> {
        val used = Array(h) { BooleanArray(w) }
        val out = ArrayList<Rect>()
        for (r in 0 until h) for (c in 0 until w) {
            val k = keys[r][c]
            if (k < 0 || used[r][c]) continue
            var c1 = c
            while (c1 + 1 < w && keys[r][c1 + 1] == k && !used[r][c1 + 1]) c1++
            var r1 = r
            while (r1 + 1 < h) {
                var ok = true
                for (cc in c..c1) if (keys[r1 + 1][cc] != k || used[r1 + 1][cc]) { ok = false; break }
                if (!ok) break
                r1++
            }
            for (rr in r..r1) for (cc in c..c1) used[rr][cc] = true
            out.add(Rect(r, c, r1, c1, k))
        }
        return out
    }
}
