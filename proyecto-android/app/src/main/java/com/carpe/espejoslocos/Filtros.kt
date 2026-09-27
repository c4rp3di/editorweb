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

/**
 * Catálogo de filtros de distorsión.
 *
 * Cada filtro implementa el mapeo inverso: para cada píxel de destino,
 * devuelve de qué píxel de origen tomarlo. Con eso OpenCV hace el resto
 * con Imgproc.remap(). Añadir un filtro nuevo son ~15 líneas.
 */
object Filtros {

    data class Filtro(
        val id: String,
        val nombre: String,
        val icono: String,
        val animado: Boolean = false,
        val distorsionar: (x: Float, y: Float, cx: Float, cy: Float, ancho: Int, alto: Int, t: Float) -> Pair<Float, Float>,
        val postProcesar: ((Mat) -> Unit)? = null
    )

    private val original = Filtro(
        id = "original", nombre = "Original", icono = "🔍",
        distorsionar = { x, y, _, _, _, _, _ -> Pair(x, y) }
    )

    private val barril = Filtro(
        id = "barril", nombre = "Barril", icono = "🪞",
        distorsionar = { x, y, cx, cy, _, _, _ ->
            val radio = min(cx, cy)
            val dx = (x - cx) / radio
            val dy = (y - cy) / radio
            val r2 = dx * dx + dy * dy
            val factor = 1f + (-0.55f) * r2
            Pair(cx + dx * factor * radio, cy + dy * factor * radio)
        }
    )

    private val cojin = Filtro(
        id = "cojin", nombre = "Cojín", icono = "🎯",
        distorsionar = { x, y, cx, cy, _, _, _ ->
            val radio = min(cx, cy)
            val dx = (x - cx) / radio
            val dy = (y - cy) / radio
            val r2 = dx * dx + dy * dy
            val factor = 1f + 0.55f * r2
            Pair(cx + dx * factor * radio, cy + dy * factor * radio)
        }
    )

    private val remolino = Filtro(
        id = "remolino", nombre = "Remolino", icono = "🌀", animado = true,
        distorsionar = { x, y, cx, cy, _, _, t ->
            val radio = min(cx, cy)
            val dx = (x - cx) / radio
            val dy = (y - cy) / radio
            val r = sqrt(dx * dx + dy * dy)
            val aBase = atan2(dy, dx)
            val fuerza = 2.8f * (1f - min(r, 1f))
            val aRot = aBase - fuerza + t * 0.8f
            Pair(cx + cos(aRot) * r * radio, cy + sin(aRot) * r * radio)
        }
    )

    private val onda = Filtro(
        id = "onda", nombre = "Onda", icono = "🌊", animado = true,
        distorsionar = { x, y, _, _, ancho, alto, t ->
            val amp = ancho * 0.03f
            val longOnda = alto * 0.18f
            val fase = t * 3.5f
            val desp = amp * sin(y / longOnda * 2f * Math.PI.toFloat() + fase)
            Pair(x + desp, y)
        }
    )

    private val lupa = Filtro(
        id = "lupa", nombre = "Lupa", icono = "🔮",
        distorsionar = { x, y, cx, cy, _, _, _ ->
            val radio = min(cx, cy)
            val dx = (x - cx) / radio
            val dy = (y - cy) / radio
            val r = sqrt(dx * dx + dy * dy)
            val R = 0.7f
            if (r >= R) Pair(x, y) else {
                val t = r / R
                val factor = 0.5f + 0.5f * t
                Pair(cx + dx * factor * radio, cy + dy * factor * radio)
            }
        }
    )

    private val alfiler = Filtro(
        id = "alfiler", nombre = "Alfiler", icono = "📌",
        distorsionar = { x, y, cx, cy, _, _, _ ->
            val radio = min(cx, cy)
            val dx = (x - cx) / radio
            val dy = (y - cy) / radio
            val r = sqrt(dx * dx + dy * dy)
            val R = 0.7f
            if (r >= R) Pair(x, y) else {
                val t = r / R
                val factor = 1f + 0.8f * (1f - t)
                Pair(cx + dx * factor * radio, cy + dy * factor * radio)
            }
        }
    )

    private val espejoFeria = Filtro(
        id = "espejo-feria", nombre = "Espejo de feria", icono = "🎪",
        distorsionar = { x, y, cx, cy, _, alto, _ ->
            val radio = min(cx, cy)
            val dx = (x - cx) / radio
            val dy = (y - cy) / radio
            val r2 = dx * dx + dy * dy
            val mezcla = (y / alto) * 2f - 1f
            val factor = 1f + mezcla * 0.55f * r2
            Pair(cx + dx * factor * radio, cy + dy * factor * radio)
        }
    )

    private val caleidoscopio = Filtro(
        id = "caleidoscopio", nombre = "Caleidoscopio", icono = "🔁",
        distorsionar = { x, y, cx, cy, _, _, _ ->
            val radio = min(cx, cy)
            val dx = (x - cx) / radio
            val dy = (y - cy) / radio
            val r = sqrt(dx * dx + dy * dy)
            val angulo = atan2(dy, dx)
            val sector = (2f * Math.PI.toFloat()) / 6f
            var a = angulo % sector
            if (a < 0) a += sector
            if (a > sector / 2f) a = sector - a
            Pair(cx + cos(a) * r * radio, cy + sin(a) * r * radio)
        }
    )

    private val glitch = Filtro(
        id = "glitch", nombre = "Glitch", icono = "🧬", animado = true,
        distorsionar = { x, y, _, _, ancho, alto, t ->
            val frame = t.toInt()
            val rnd = Random(y.toInt() / (alto / 20 + 1) + frame * 31)
            val desp = if (rnd.nextFloat() < 0.15f) {
                rnd.nextFloat() * ancho * 0.15f - ancho * 0.075f
            } else 0f
            Pair(x + desp, y)
        }
    )

    private val cromatica = Filtro(
        id = "cromatica", nombre = "Cromática", icono = "🌈",
        distorsionar = { x, y, _, _, _, _, _ -> Pair(x, y) },
        postProcesar = { mat ->
            val canales = ArrayList<Mat>(4)
            Core.split(mat, canales)
            val mR = Mat()
            val mB = Mat()
            val tR = Mat(2, 3, CvType.CV_64FC1)
            tR.put(0, 0, 1.0, 0.0, 2.5, 0.0, 1.0, 0.0)
            val tB = Mat(2, 3, CvType.CV_64FC1)
            tB.put(0, 0, 1.0, 0.0, -2.5, 0.0, 1.0, 0.0)
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
        espejoFeria, caleidoscopio, glitch, cromatica
    )

    /**
     * Construye los mapas de remapeo para un frame de (ancho × alto).
     * La intensidad interpola linealmente entre la posición original y la
     * distorsionada: 0f = sin efecto, 1f = efecto completo del filtro.
     */
    fun crearMapas(filtro: Filtro, ancho: Int, alto: Int, intensidad: Float, tiempo: Float): Pair<Mat, Mat> {
        val mapX = Mat(alto, ancho, CvType.CV_32FC1)
        val mapY = Mat(alto, ancho, CvType.CV_32FC1)
        val datosX = FloatArray(ancho * alto)
        val datosY = FloatArray(ancho * alto)
        val cx = ancho / 2f
        val cy = alto / 2f
        var idx = 0
        for (y in 0 until alto) {
            for (x in 0 until ancho) {
                val (dx, dy) = filtro.distorsionar(x.toFloat(), y.toFloat(), cx, cy, ancho, alto, tiempo)
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