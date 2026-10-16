package com.vigia.app.analyze.base

import com.vigia.app.analyze.AjustesAnalisis
import com.vigia.app.analyze.DatosSesion
import com.vigia.app.analyze.Hallazgo
import com.vigia.app.analyze.Severidad
import com.vigia.app.store.AnalisisDao
import com.vigia.app.store.BaselineEntity
import com.vigia.app.store.BaselineSampleEntity
import com.vigia.app.util.Formato
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

class Estad(
    val media: Double,
    val desviacion: Double,
    val p5: Double,
    val p50: Double,
    val p95: Double,
    val muestras: Int
)

/**
 * Línea base estadística (14 días móviles). Sin IA ni modelos opacos: media, desviación y percentiles
 * por métrica y hora del día. Un valor es anómalo si se sale de los percentiles 5/95 Y se desvía más
 * de 2 desviaciones de la media (y al menos 3 unidades, para no marcar diferencias minúsculas).
 */
object MotorBase {
    const val DNS_CONSULTAS = "dns_consultas"
    const val DNS_DOMINIOS = "dns_dominios"
    const val PANTALLA_EVENTOS = "pantalla_eventos"
    const val SENSORES_ACCESOS = "sensores_accesos"
    const val APPS_PRIMER_PLANO = "apps_primer_plano"

    const val VENTANA_DIAS = 14L
    private const val HORA_MS = 3_600_000L
    private const val DIA_MS = 86_400_000L
    private const val Z_UMBRAL = 2.0
    private const val DIFERENCIA_MIN = 3.0

    fun nombreMetrica(m: String): String {
        return when (m) {
            DNS_CONSULTAS -> "consultas DNS por hora"
            DNS_DOMINIOS -> "dominios distintos por hora"
            PANTALLA_EVENTOS -> "eventos de pantalla por hora"
            SENSORES_ACCESOS -> "usos de cámara o micrófono por hora"
            APPS_PRIMER_PLANO -> "apps distintas en pantalla por hora"
            else -> m
        }
    }

    class Cubo(val dia: Long, val hora: Int, val inicio: Long, val valores: Map<String, Int>)

    fun diaLocal(ts: Long): Long {
        val off = TimeZone.getDefault().getOffset(ts).toLong()
        return Math.floorDiv(ts + off, DIA_MS)
    }

    /** Horas completas de la sesión con el valor de cada métrica que se pudo medir. */
    fun cubos(d: DatosSesion): List<Cubo> {
        val off = TimeZone.getDefault().getOffset(d.inicio).toLong()
        val primera = Math.floorDiv(d.inicio + off + HORA_MS - 1L, HORA_MS)
        val finExcl = Math.floorDiv(d.fin + off, HORA_MS)
        if (finExcl <= primera) return emptyList()

        val consultas = HashMap<Long, Int>()
        val dominios = HashMap<Long, HashSet<String>>()
        val pantalla = HashMap<Long, Int>()
        val appsPantalla = HashMap<Long, HashSet<String>>()
        val sensores = HashMap<Long, Int>()

        for (e in d.dns) {
            val h = Math.floorDiv(e.ts + off, HORA_MS)
            consultas[h] = (consultas[h] ?: 0) + 1
            dominios.getOrPut(h) { HashSet() }.add(e.dominio)
        }
        for (e in d.pantalla) {
            val h = Math.floorDiv(e.ts + off, HORA_MS)
            pantalla[h] = (pantalla[h] ?: 0) + 1
            if (e.tipoEvento == "ventana") appsPantalla.getOrPut(h) { HashSet() }.add(e.app)
        }
        for (e in d.sensores) {
            if (!e.modo.startsWith("en_uso")) continue
            val h = Math.floorDiv(e.ts + off, HORA_MS)
            sensores[h] = (sensores[h] ?: 0) + 1
        }

        val res = ArrayList<Cubo>()
        var h = primera
        while (h < finExcl) {
            val valores = LinkedHashMap<String, Int>()
            if (d.vpn) {
                valores[DNS_CONSULTAS] = consultas[h] ?: 0
                valores[DNS_DOMINIOS] = dominios[h]?.size ?: 0
            }
            if (d.modulos.a11y) {
                valores[PANTALLA_EVENTOS] = pantalla[h] ?: 0
                valores[APPS_PRIMER_PLANO] = appsPantalla[h]?.size ?: 0
            }
            if (d.modulos.sensores) {
                valores[SENSORES_ACCESOS] = sensores[h] ?: 0
            }
            res.add(Cubo(Math.floorDiv(h, 24L), Math.floorMod(h, 24L).toInt(), h * HORA_MS - off, valores))
            h++
        }
        return res
    }

    fun estadisticas(valores: List<Int>): Estad {
        val n = valores.size
        val ordenados = valores.sorted()
        var suma = 0.0
        for (v in valores) suma += v
        val media = suma / n
        var cuadros = 0.0
        for (v in valores) cuadros += (v - media) * (v - media)
        val desv = sqrt(cuadros / n)
        return Estad(
            media, desv,
            percentil(ordenados, 5.0), percentil(ordenados, 50.0), percentil(ordenados, 95.0), n
        )
    }

    private fun percentil(ordenados: List<Int>, p: Double): Double {
        val n = ordenados.size
        var rango = ceil(p / 100.0 * n).toInt() - 1
        if (rango < 0) rango = 0
        if (rango > n - 1) rango = n - 1
        return ordenados[rango].toDouble()
    }

    private fun clave(metrica: String, hora: Int): String = metrica + "|" + hora

    /** Detecta anomalías de esta sesión contra la línea base de las demás. También devuelve las muestras de la sesión. */
    suspend fun analizar(
        dao: AnalisisDao, d: DatosSesion, ajustes: AjustesAnalisis
    ): Pair<List<Hallazgo>, List<BaselineSampleEntity>> {
        val res = ArrayList<Hallazgo>()
        val minMuestras = ajustes.entero(AjustesAnalisis.BASE_MIN_MUESTRAS, 5)
        val minDia = diaLocal(d.fin) - VENTANA_DIAS
        val cubos = cubos(d)

        val previas = HashMap<String, MutableList<Int>>()
        for (m in dao.muestrasExcepto(minDia, d.sesion.id)) {
            previas.getOrPut(clave(m.metrica, m.hora)) { ArrayList() }.add(m.valor)
        }

        val nuevas = ArrayList<BaselineSampleEntity>()
        var evaluados = 0
        var enAprendizaje = 0
        val anomalias = ArrayList<Pair<Double, Hallazgo>>()

        for (c in cubos) {
            for ((metrica, valor) in c.valores) {
                nuevas.add(BaselineSampleEntity(sessionId = d.sesion.id, metrica = metrica, dia = c.dia, hora = c.hora, valor = valor))
                val lista = previas[clave(metrica, c.hora)]
                if (lista == null || lista.size < minMuestras) {
                    enAprendizaje++
                    continue
                }
                evaluados++
                val s = estadisticas(lista)
                val diferencia = abs(valor - s.media)
                val fuera = valor > s.p95 || valor < s.p5
                val z = if (s.desviacion > 0.0) diferencia / s.desviacion else Double.MAX_VALUE
                if (fuera && z > Z_UMBRAL && diferencia >= DIFERENCIA_MIN) {
                    val alto = valor > s.media
                    val sev = if (alto && z > 3.0) Severidad.MEDIA else Severidad.BAJA
                    val conf = if (z > 3.0) 55 else 40
                    val texto = "Entre las " + Formato.hora(c.inicio).substring(0, 5) + " y la hora siguiente hubo " + valor + " " +
                        nombreMetrica(metrica) + (if (alto) ", por encima" else ", por debajo") +
                        " de lo habitual (media " + String.format("%.1f", s.media) + ", desviación " +
                        String.format("%.1f", s.desviacion) + ", percentil 95 = " + String.format("%.0f", s.p95) +
                        ", con " + s.muestras + " muestras de los últimos 14 días)."
                    val orden = if (z == Double.MAX_VALUE) 1000.0 else z
                    anomalias.add(
                        Pair(
                            orden,
                            Hallazgo(c.inicio, sev, "base", "BASE", "", "Actividad fuera de lo habitual", texto, emptyList(), conf)
                        )
                    )
                }
            }
        }

        anomalias.sortByDescending { it.first }
        for (par in anomalias.take(30)) res.add(par.second)

        if (cubos.isEmpty()) {
            res.add(nota("Línea base: la sesión no tiene ninguna hora completa que medir (hacen falta al menos 60 minutos alineados con la hora)."))
        } else if (evaluados == 0) {
            res.add(
                nota(
                    "Línea base en aprendizaje: todavía no hay " + minMuestras +
                        " muestras previas por franja. No se generan alertas hasta entonces; esta sesión ya aporta " +
                        nuevas.size + " muestras."
                )
            )
        }
        return Pair(res, nuevas)
    }

    private fun nota(texto: String): Hallazgo {
        return Hallazgo(0L, Severidad.INFO, "base", "NOTA", "", "Línea base", texto, emptyList(), 100)
    }

    /** Guarda las muestras de la sesión, descarta las de más de 14 días y recalcula las estadísticas. */
    suspend fun guardar(dao: AnalisisDao, sesionId: Long, muestras: List<BaselineSampleEntity>, hoy: Long) {
        dao.borrarMuestrasDeSesion(sesionId)
        var i = 0
        while (i < muestras.size) {
            dao.insertarMuestras(muestras.subList(i, minOf(i + 500, muestras.size)))
            i += 500
        }
        dao.borrarMuestrasAntiguas(hoy - VENTANA_DIAS)
        recalcular(dao, hoy)
    }

    suspend fun recalcular(dao: AnalisisDao, hoy: Long) {
        val grupos = LinkedHashMap<String, MutableList<Int>>()
        for (m in dao.todasMuestras(hoy - VENTANA_DIAS)) {
            grupos.getOrPut(clave(m.metrica, m.hora)) { ArrayList() }.add(m.valor)
        }
        val ahora = System.currentTimeMillis()
        val filas = ArrayList<BaselineEntity>()
        for ((k, lista) in grupos) {
            val metrica = k.substringBefore('|')
            val hora = k.substringAfter('|').toInt()
            val s = estadisticas(lista)
            filas.add(
                BaselineEntity(
                    metrica = metrica, ventana = "h" + String.format("%02d", hora),
                    media = s.media, desviacion = s.desviacion, p5 = s.p5, p50 = s.p50, p95 = s.p95,
                    muestras = s.muestras, actualizadoTs = ahora
                )
            )
        }
        dao.borrarBaseline()
        if (filas.isNotEmpty()) dao.guardarBaseline(filas)
    }
}
