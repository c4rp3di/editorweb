package com.vigia.app.informe

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.vigia.app.analyze.Severidad
import com.vigia.app.util.Formato
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** PDF legible del informe, hecho con el motor de PDF de Android (sin librerías externas). */
object InformePdf {
    private const val ANCHO = 595
    private const val ALTO = 842
    private const val MARGEN = 40f

    private class Linea(val texto: String, val tamano: Float, val negrita: Boolean, val espacioDespues: Float)

    fun generar(contenido: JSONObject): ByteArray {
        val lineas = componer(contenido)
        val documento = PdfDocument()
        val pincel = Paint()
        pincel.isAntiAlias = true
        val anchoUtil = ANCHO - 2 * MARGEN

        var numero = 1
        var pagina = documento.startPage(PdfDocument.PageInfo.Builder(ANCHO, ALTO, numero).create())
        var lienzo = pagina.canvas
        var y = MARGEN

        for (l in lineas) {
            pincel.textSize = l.tamano
            pincel.isFakeBoldText = l.negrita
            val alto = l.tamano * 1.35f
            for (parte in envolver(l.texto, pincel, anchoUtil)) {
                if (y + alto > ALTO - MARGEN) {
                    documento.finishPage(pagina)
                    numero++
                    pagina = documento.startPage(PdfDocument.PageInfo.Builder(ANCHO, ALTO, numero).create())
                    lienzo = pagina.canvas
                    y = MARGEN
                }
                y += alto
                lienzo.drawText(parte, MARGEN, y, pincel)
            }
            y += l.espacioDespues
        }
        documento.finishPage(pagina)
        val salida = ByteArrayOutputStream()
        documento.writeTo(salida)
        documento.close()
        return salida.toByteArray()
    }

    private fun componer(c: JSONObject): List<Linea> {
        val res = ArrayList<Linea>()
        val sesion = c.optJSONObject("sesion") ?: JSONObject()
        val resumen = c.optJSONObject("resumen") ?: JSONObject()

        res.add(Linea("Vigía · Informe de la sesión #" + sesion.optLong("id"), 20f, true, 6f))
        res.add(
            Linea(
                "Generado el " + Formato.fecha(c.optLong("generado")) + " · sesión del " + Formato.fecha(sesion.optLong("inicio")) +
                    " al " + Formato.fecha(sesion.optLong("fin")),
                10f, false, 12f
            )
        )

        res.add(Linea("Resumen ejecutivo", 14f, true, 4f))
        res.add(Linea(resumen.optString("texto"), 11f, false, 8f))
        res.add(
            Linea(
                "Críticos: " + resumen.optInt("criticas") + " · Altos: " + resumen.optInt("altas") + " · Medios: " +
                    resumen.optInt("medias") + " · Bajos: " + resumen.optInt("bajas") + " · Informativos: " + resumen.optInt("informativas"),
                11f, true, 12f
            )
        )

        val modulos = sesion.optJSONObject("modulos") ?: JSONObject()
        val activos = ArrayList<String>()
        if (sesion.optBoolean("captura_dns")) activos.add("captura DNS")
        if (modulos.optBoolean("eventos")) activos.add("eventos del sistema")
        if (modulos.optBoolean("inventario")) activos.add("inventario")
        if (modulos.optBoolean("sensores")) activos.add("cámara y micrófono")
        if (modulos.optBoolean("accesibilidad")) activos.add("actividad en pantalla")
        res.add(Linea("Qué se midió", 14f, true, 4f))
        res.add(Linea("Módulos activos: " + (if (activos.isEmpty()) "ninguno" else activos.joinToString(", ")) + ".", 11f, false, 12f))

        res.add(Linea("Hallazgos", 14f, true, 4f))
        val hallazgos = c.optJSONArray("hallazgos")
        var mostrados = 0
        if (hallazgos != null) {
            for (i in 0 until hallazgos.length()) {
                val h = hallazgos.optJSONObject(i) ?: continue
                res.add(
                    Linea(
                        "[" + Severidad.etiqueta(h.optString("severidad")) + " · " + h.optString("regla") + " · confianza " +
                            h.optInt("confianza") + "%] " + h.optString("titulo"),
                        11f, true, 1f
                    )
                )
                val app = h.optString("app")
                res.add(Linea(Formato.fecha(h.optLong("ts")) + (if (app.isEmpty()) "" else " · " + app), 9f, false, 1f))
                res.add(Linea(h.optString("descripcion"), 10f, false, 8f))
                mostrados++
            }
        }
        if (mostrados == 0) res.add(Linea("Sin hallazgos.", 11f, false, 8f))
        res.add(Linea("", 6f, false, 6f))

        val stats = c.optJSONObject("estadisticas") ?: JSONObject()
        res.add(Linea("Estadísticas agregadas", 14f, true, 4f))
        res.add(
            Linea(
                "Consultas DNS: " + stats.optInt("consultas_dns") + " · dominios únicos: " + stats.optInt("dominios_unicos") +
                    " · apps con DNS: " + stats.optInt("apps_con_dns") + "\nEventos del sistema: " + stats.optInt("eventos_sistema") +
                    " · cámara/micrófono: " + stats.optInt("eventos_sensores") + " · pantalla: " + stats.optInt("eventos_pantalla"),
                11f, false, 6f
            )
        )
        val top = stats.optJSONArray("top_apps_dns")
        if (top != null && top.length() > 0) {
            res.add(Linea("Apps con más consultas DNS:", 11f, true, 2f))
            for (i in 0 until top.length()) {
                val a = top.optJSONObject(i) ?: continue
                res.add(Linea("  • " + a.optString("app") + ": " + a.optInt("consultas"), 10f, false, 1f))
            }
        }
        val red = stats.optJSONObject("red")
        if (red != null) {
            res.add(Linea("Conexiones de red (captura de conexiones)", 12f, true, 2f))
            res.add(
                Linea(
                    "Conexiones: " + red.optInt("conexiones") + " · destinos distintos: " + red.optInt("destinos_unicos") +
                        " · tráfico total: " + String.format("%.1f MB", red.optLong("bytes_totales") / 1048576.0),
                    11f, false, 2f
                )
            )
            val apps = red.optJSONArray("top_apps_bytes")
            if (apps != null && apps.length() > 0) {
                res.add(Linea("Apps con más tráfico:", 11f, true, 2f))
                for (i in 0 until apps.length()) {
                    val a = apps.optJSONObject(i) ?: continue
                    res.add(Linea("  • " + a.optString("app") + ": " + String.format("%.1f MB", a.optLong("bytes") / 1048576.0), 10f, false, 1f))
                }
            }
            val destinos = red.optJSONArray("top_destinos")
            if (destinos != null && destinos.length() > 0) {
                res.add(Linea("Destinos con más datos enviados:", 11f, true, 2f))
                for (i in 0 until destinos.length()) {
                    val x = destinos.optJSONObject(i) ?: continue
                    res.add(
                        Linea(
                            "  • " + x.optString("ip") + ": enviados " + String.format("%.1f MB", x.optLong("enviado") / 1048576.0) +
                                ", recibidos " + String.format("%.1f MB", x.optLong("recibido") / 1048576.0),
                            10f, false, 1f
                        )
                    )
                }
            }
        }
        res.add(Linea("", 6f, false, 6f))

        val base = c.optJSONObject("linea_base") ?: JSONObject()
        res.add(Linea("Línea base", 14f, true, 4f))
        res.add(Linea("Estado: " + base.optString("estado") + " · muestras guardadas: " + base.optInt("muestras_guardadas"), 11f, false, 2f))
        val metricas = base.optJSONArray("metricas_de_la_sesion")
        if (metricas != null) {
            for (i in 0 until metricas.length()) {
                val m = metricas.optJSONObject(i) ?: continue
                res.add(
                    Linea(
                        "  • " + m.optString("metrica") + ": " + m.optInt("horas_medidas") + " horas, media " +
                            m.optDouble("media_por_hora") + ", máximo " + m.optInt("maximo_por_hora"),
                        10f, false, 1f
                    )
                )
            }
        }
        res.add(Linea("", 6f, false, 8f))
        res.add(
            Linea(
                "Esto es un conjunto de señales anómalas que merecen revisión, no una prueba de infección. " +
                    "Un informe sin hallazgos tampoco demuestra que el teléfono esté limpio.",
                9f, false, 0f
            )
        )
        return res
    }

    /** Parte el texto en líneas que caben en el ancho, cortando palabras larguísimas si hace falta. */
    private fun envolver(texto: String, pincel: Paint, ancho: Float): List<String> {
        val res = ArrayList<String>()
        for (parrafo in texto.split('\n')) {
            if (parrafo.isEmpty()) {
                res.add("")
                continue
            }
            var actual = StringBuilder()
            for (palabra in parrafo.split(' ')) {
                var p = palabra
                while (pincel.measureText(p) > ancho) {
                    if (actual.isNotEmpty()) {
                        res.add(actual.toString())
                        actual = StringBuilder()
                    }
                    var corte = p.length
                    while (corte > 1 && pincel.measureText(p.substring(0, corte)) > ancho) corte--
                    res.add(p.substring(0, corte))
                    p = p.substring(corte)
                }
                val prueba = if (actual.isEmpty()) p else actual.toString() + " " + p
                if (pincel.measureText(prueba) <= ancho) {
                    actual = StringBuilder(prueba)
                } else {
                    res.add(actual.toString())
                    actual = StringBuilder(p)
                }
            }
            res.add(actual.toString())
        }
        return res
    }
}
