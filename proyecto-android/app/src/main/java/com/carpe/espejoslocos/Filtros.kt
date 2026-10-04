package com.carpe.espejoslocos

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object Filtros {

    data class Parametro(
        val id: String,
        val etiqueta: String,
        val min: Float,
        val max: Float,
        val porDefecto: Float,
        val esEntero: Boolean = false
    )

    data class Contexto(
        val x: Float, val y: Float,
        val cx: Float, val cy: Float,
        val ancho: Int, val alto: Int,
        val t: Float,
        val intensidad: Float,
        val params: Map<String, Float>,
        val toqueActivo: Boolean = false,
        val toqueX: Float = 0f,
        val toqueY: Float = 0f
    ) {
        fun p(id: String, alt: Float = 0f) = params[id] ?: alt
    }

    data class Filtro(
        val id: String,
        val nombre: String,
        val icono: String,
        val animado: Boolean = false,
        val parametros: List<Parametro> = emptyList(),
        val distorsionar: (Contexto) -> Pair<Float, Float>,
        val postProcesar: ((Mat, Map<String, Float>) -> Unit)? = null
    )

    // Estado por filtro: cada filtro recuerda sus valores entre cambios.
    private val valores: MutableMap<String, MutableMap<String, Float>> = mutableMapOf()

    fun getParam(filtro: Filtro, paramId: String): Float {
        val mapa = valores.getOrPut(filtro.id) { mutableMapOf() }
        return mapa.getOrPut(paramId) {
            filtro.parametros.find { it.id == paramId }?.porDefecto ?: 0f
        }
    }

    fun setParam(filtro: Filtro, paramId: String, valor: Float) {
        val mapa = valores.getOrPut(filtro.id) { mutableMapOf() }
        val def = filtro.parametros.find { it.id == paramId }
        mapa[paramId] = if (def != null) valor.coerceIn(def.min, def.max) else valor
    }

    fun mapParams(filtro: Filtro): Map<String, Float> {
        val mapa = valores.getOrPut(filtro.id) { mutableMapOf() }
        return filtro.parametros.associate { it.id to mapa.getOrPut(it.id) { it.porDefecto } }
    }

    private fun deformacionTactil(c: Contexto, tipo: Int): Pair<Float, Float> {
        if (!c.toqueActivo) return Pair(c.x, c.y)

        val radioBase = min(c.ancho, c.alto).toFloat()
        val radio = (c.p("radio", 0.35f) * radioBase).coerceAtLeast(1f)
        val dx = c.x - c.toqueX
        val dy = c.y - c.toqueY
        val distancia = sqrt(dx * dx + dy * dy)
        if (distancia >= radio) return Pair(c.x, c.y)

        val t = (distancia / radio).coerceIn(0f, 1f)
        val influencia = (1f - t) * (1f - t)
        val fuerza = c.p("fuerza", 1f) * c.intensidad

        return when (tipo) {
            0 -> { // Inflar: acerca la fuente al punto tocado.
                val factor = (1f - 0.55f * fuerza * influencia).coerceAtLeast(0.18f)
                Pair(c.toqueX + dx * factor, c.toqueY + dy * factor)
            }
            1 -> { // Hundir: aleja la fuente del punto tocado.
                val factor = 1f + 0.70f * fuerza * influencia
                Pair(c.toqueX + dx * factor, c.toqueY + dy * factor)
            }
            else -> { // Remolino: rota alrededor del punto tocado.
                val angulo = atan2(dy, dx) + c.p("fuerza", 1f) * 2.4f * influencia
                val r = distancia
                Pair(c.toqueX + cos(angulo) * r, c.toqueY + sin(angulo) * r)
            }
        }
    }

    // ---------- Filtros ----------

    private val original = Filtro(
        id = "original", nombre = "Original", icono = "🔍",
        distorsionar = { c -> Pair(c.x, c.y) }
    )

    private val barril = Filtro(
        id = "barril", nombre = "Barril", icono = "🪞",
        parametros = listOf(Parametro("curvatura", "Curvatura", 0.1f, 1.5f, 0.55f)),
        distorsionar = { c ->
            val radio = min(c.cx, c.cy)
            val dx = (c.x - c.cx) / radio
            val dy = (c.y - c.cy) / radio
            val r2 = dx * dx + dy * dy
            val factor = 1f + (-c.p("curvatura", 0.55f)) * r2
            Pair(c.cx + dx * factor * radio, c.cy + dy * factor * radio)
        }
    )

    private val cojin = Filtro(
        id = "cojin", nombre = "Cojín", icono = "🎯",
        parametros = listOf(Parametro("curvatura", "Curvatura", 0.1f, 1.5f, 0.55f)),
        distorsionar = { c ->
            val radio = min(c.cx, c.cy)
            val dx = (c.x - c.cx) / radio
            val dy = (c.y - c.cy) / radio
            val r2 = dx * dx + dy * dy
            val factor = 1f + c.p("curvatura", 0.55f) * r2
            Pair(c.cx + dx * factor * radio, c.cy + dy * factor * radio)
        }
    )

    private val remolino = Filtro(
        id = "remolino", nombre = "Remolino", icono = "🌀", animado = true,
        parametros = listOf(
            Parametro("fuerza", "Fuerza", 0.5f, 6f, 2.8f),
            Parametro("velocidad", "Velocidad", -3f, 3f, 0.8f)
        ),
        distorsionar = { c ->
            val radio = min(c.cx, c.cy)
            val dx = (c.x - c.cx) / radio
            val dy = (c.y - c.cy) / radio
            val r = sqrt(dx * dx + dy * dy)
            val aBase = atan2(dy, dx)
            val fuerza = c.p("fuerza", 2.8f) * (1f - min(r, 1f))
            val aRot = aBase - fuerza + c.t * c.p("velocidad", 0.8f)
            Pair(c.cx + cos(aRot) * r * radio, c.cy + sin(aRot) * r * radio)
        }
    )

    private val onda = Filtro(
        id = "onda", nombre = "Onda", icono = "🌊", animado = true,
        parametros = listOf(
            Parametro("amplitud", "Amplitud", 0f, 0.1f, 0.03f),
            Parametro("longitud", "Longitud", 0.05f, 0.5f, 0.18f),
            Parametro("velocidad", "Velocidad", -8f, 8f, 3.5f)
        ),
        distorsionar = { c ->
            val amp = c.ancho * c.p("amplitud", 0.03f)
            val longOnda = c.alto * c.p("longitud", 0.18f)
            val fase = c.t * c.p("velocidad", 3.5f)
            val desp = amp * sin(c.y / longOnda * 2f * Math.PI.toFloat() + fase)
            Pair(c.x + desp, c.y)
        }
    )

    private val lupa = Filtro(
        id = "lupa", nombre = "Lupa", icono = "🔮",
        parametros = listOf(
            Parametro("radio", "Radio", 0.2f, 1.0f, 0.7f),
            Parametro("zoom", "Zoom", 0.2f, 1.0f, 0.5f)
        ),
        distorsionar = { c ->
            val radio = min(c.cx, c.cy)
            val dx = (c.x - c.cx) / radio
            val dy = (c.y - c.cy) / radio
            val r = sqrt(dx * dx + dy * dy)
            val R = c.p("radio", 0.7f)
            if (r >= R) Pair(c.x, c.y) else {
                val t = r / R
                val zoom = c.p("zoom", 0.5f)
                val factor = zoom + (1f - zoom) * t
                Pair(c.cx + dx * factor * radio, c.cy + dy * factor * radio)
            }
        }
    )

    private val alfiler = Filtro(
        id = "alfiler", nombre = "Alfiler", icono = "📌",
        parametros = listOf(
            Parametro("radio", "Radio", 0.2f, 1.0f, 0.7f),
            Parametro("zoom", "Zoom", 0.5f, 2.5f, 1.8f)
        ),
        distorsionar = { c ->
            val radio = min(c.cx, c.cy)
            val dx = (c.x - c.cx) / radio
            val dy = (c.y - c.cy) / radio
            val r = sqrt(dx * dx + dy * dy)
            val R = c.p("radio", 0.7f)
            if (r >= R) Pair(c.x, c.y) else {
                val t = r / R
                val zoom = c.p("zoom", 1.8f)
                val factor = 1f + (zoom - 1f) * (1f - t)
                Pair(c.cx + dx * factor * radio, c.cy + dy * factor * radio)
            }
        }
    )

    private val espejoFeria = Filtro(
        id = "espejo-feria", nombre = "Espejo de feria", icono = "🎪",
        parametros = listOf(Parametro("curvatura", "Curvatura", 0.1f, 1.0f, 0.55f)),
        distorsionar = { c ->
            val radio = min(c.cx, c.cy)
            val dx = (c.x - c.cx) / radio
            val dy = (c.y - c.cy) / radio
            val r2 = dx * dx + dy * dy
            val mezcla = (c.y / c.alto) * 2f - 1f
            val factor = 1f + mezcla * c.p("curvatura", 0.55f) * r2
            Pair(c.cx + dx * factor * radio, c.cy + dy * factor * radio)
        }
    )

    private val caleidoscopio = Filtro(
        id = "caleidoscopio", nombre = "Caleidoscopio", icono = "🔁",
        parametros = listOf(Parametro("sectores", "Sectores", 3f, 12f, 6f, esEntero = true)),
        distorsionar = { c ->
            val radio = min(c.cx, c.cy)
            val dx = (c.x - c.cx) / radio
            val dy = (c.y - c.cy) / radio
            val r = sqrt(dx * dx + dy * dy)
            val angulo = atan2(dy, dx)
            val sector = (2f * Math.PI.toFloat()) / c.p("sectores", 6f).coerceAtLeast(3f)
            var a = angulo % sector
            if (a < 0) a += sector
            if (a > sector / 2f) a = sector - a
            Pair(c.cx + cos(a) * r * radio, c.cy + sin(a) * r * radio)
        }
    )

    private val glitch = Filtro(
        id = "glitch", nombre = "Glitch", icono = "🧬", animado = true,
        parametros = listOf(
            Parametro("densidad", "Densidad", 0f, 0.5f, 0.15f),
            Parametro("magnitud", "Magnitud", 0f, 0.3f, 0.15f)
        ),
        distorsionar = { c ->
            val frame = c.t.toInt()
            val rnd = Random(c.y.toInt() / (c.alto / 20 + 1) + frame * 31)
            val desp = if (rnd.nextFloat() < c.p("densidad", 0.15f)) {
                (rnd.nextFloat() * 2f - 1f) * c.ancho * c.p("magnitud", 0.15f)
            } else 0f
            Pair(c.x + desp, c.y)
        }
    )

    private val inflarTactil = Filtro(
        id = "inflar-tactil", nombre = "Inflar táctil", icono = "🫧",
        parametros = listOf(
            Parametro("radio", "Radio", 0.08f, 0.80f, 0.32f),
            Parametro("fuerza", "Fuerza", 0.10f, 2.00f, 1.00f)
        ),
        distorsionar = { c -> deformacionTactil(c, 0) }
    )

    private val hundirTactil = Filtro(
        id = "hundir-tactil", nombre = "Hundir táctil", icono = "🕳️",
        parametros = listOf(
            Parametro("radio", "Radio", 0.08f, 0.80f, 0.32f),
            Parametro("fuerza", "Fuerza", 0.10f, 2.00f, 1.00f)
        ),
        distorsionar = { c -> deformacionTactil(c, 1) }
    )

    private val remolinoTactil = Filtro(
        id = "remolino-tactil", nombre = "Remolino táctil", icono = "🌀",
        parametros = listOf(
            Parametro("radio", "Radio", 0.08f, 0.80f, 0.32f),
            Parametro("fuerza", "Fuerza", -2.00f, 2.00f, 1.00f)
        ),
        distorsionar = { c -> deformacionTactil(c, 2) }
    )

    private val cromatica = Filtro(
        id = "cromatica", nombre = "Cromática", icono = "🌈",
        parametros = listOf(Parametro("desplazamiento", "Desplazamiento", 0f, 8f, 2.5f)),
        distorsionar = { c -> Pair(c.x, c.y) },
        postProcesar = { mat, params ->
            val desp = params["desplazamiento"] ?: 2.5f
            val canales = ArrayList<Mat>(4)
            Core.split(mat, canales)
            val mR = Mat()
            val mB = Mat()
            val tR = Mat(2, 3, CvType.CV_64FC1)
            tR.put(0, 0, 1.0, 0.0, desp.toDouble(), 0.0, 1.0, 0.0)
            val tB = Mat(2, 3, CvType.CV_64FC1)
            tB.put(0, 0, 1.0, 0.0, -desp.toDouble(), 0.0, 1.0, 0.0)
            Imgproc.warpAffine(canales[0], mR, tR, mat.size())
            Imgproc.warpAffine(canales[2], mB, tB, mat.size())
            canales[0].release()
            canales[2].release()
            canales[0] = mR
            canales[2] = mB
            Core.merge(canales, mat)
            canales.forEach { it.release() }
            tR.release()
            tB.release()
        }
    )

    val lista: List<Filtro> = listOf(
        original, barril, cojin, remolino, onda, lupa, alfiler,
        espejoFeria, caleidoscopio, glitch, cromatica,
        inflarTactil, hundirTactil, remolinoTactil
    )

    fun crearMapas(
        filtro: Filtro,
        ancho: Int, alto: Int,
        intensidad: Float, tiempo: Float,
        params: Map<String, Float>
    ): Pair<Mat, Mat> {
        val mapX = Mat(alto, ancho, CvType.CV_32FC1)
        val mapY = Mat(alto, ancho, CvType.CV_32FC1)
        val datosX = FloatArray(ancho * alto)
        val datosY = FloatArray(ancho * alto)
        val cx = ancho / 2f
        val cy = alto / 2f
        var idx = 0
        for (y in 0 until alto) {
            for (x in 0 until ancho) {
                val ctx = Contexto(
                    x.toFloat(), y.toFloat(), cx, cy,
                    ancho, alto, tiempo, intensidad, params,
                    MotorCamara.toqueActivo,
                    MotorCamara.toqueX * ancho,
                    MotorCamara.toqueY * alto
                )
                val (dx, dy) = filtro.distorsionar(ctx)
                datosX[idx] = x + (dx - x) * intensidad
                datosY[idx] = y + (dy - y) * intensidad
                idx++
            }
        }
        mapX.put(0, 0, datosX)
        mapY.put(0, 0, datosY)
        return Pair(mapX, mapY)
    }
}