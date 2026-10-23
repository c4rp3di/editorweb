package com.luzcontrol.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max

data class BarEntry(
    val label: String,
    val value: Double,
    val color: Color? = null,
    /** Texto extra para el tooltip (p. ej. periodo tarifario). */
    val detail: String? = null
)

data class DonutSlice(val label: String, val value: Double, val color: Color)

private fun niceMax(v: Double): Double {
    if (v <= 0.0) return 1.0
    return v * 1.1
}

/**
 * Gráfico de barras con línea opcional superpuesta en un segundo eje (derecha).
 * Toca una barra para ver el detalle. Dibujado directamente con Canvas (sin dependencias externas).
 */
@Composable
fun BarLineChart(
    bars: List<BarEntry>,
    modifier: Modifier = Modifier,
    line: List<Double?>? = null,
    barColor: Color = MaterialTheme.colorScheme.primary,
    lineColor: Color = MaterialTheme.colorScheme.tertiary,
    barUnit: String = "kWh",
    lineUnit: String = "€/kWh",
    lineDecimals: Int = 3,
    /** true: barras y línea comparten escala (p. ej. kW máximos frente a kW contratados). */
    sharedAxis: Boolean = false,
    barLegend: String? = null,
    lineLegend: String? = null,
    height: Dp = 220.dp
) {
    if (bars.isEmpty()) return
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = axisColor)
    val hasLine = line != null && line.any { it != null }

    val summary = "Gráfico de ${bars.size} barras, máximo ${Fmt.num(bars.maxOf { it.value })} $barUnit"

    Column(modifier.fillMaxWidth()) {
        val sel = selected
        val tip = if (sel != null && sel in bars.indices) {
            val b = bars[sel]
            buildString {
                append(b.label).append(": ").append(Fmt.num(b.value)).append(' ').append(barUnit)
                val l = line?.getOrNull(sel)
                if (l != null) append(" · ").append(Fmt.num(l, lineDecimals)).append(' ').append(lineUnit)
                if (b.detail != null) append(" · ").append(b.detail)
            }
        } else "Toca una barra para ver el detalle"
        Text(tip, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = summary }
                .pointerInput(bars) {
                    detectTapGestures { offset ->
                        val left = 44.dp.toPx()
                        val right = if (hasLine && !sharedAxis) 44.dp.toPx() else 8.dp.toPx()
                        val w = size.width - left - right
                        if (w > 0f) {
                            val idx = ((offset.x - left) / (w / bars.size)).toInt()
                            selected = if (idx in bars.indices) idx else null
                        }
                    }
                }
        ) {
            val left = 44.dp.toPx()
            val right = if (hasLine && !sharedAxis) 44.dp.toPx() else 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = 22.dp.toPx()
            val w = size.width - left - right
            val h = size.height - top - bottom
            if (w <= 0f || h <= 0f) return@Canvas

            val lineMaxRaw = line?.filterNotNull()?.maxOrNull() ?: 0.0
            val maxBar = niceMax(if (sharedAxis) max(bars.maxOf { it.value }, lineMaxRaw) else bars.maxOf { it.value })
            // Rejilla y eje izquierdo
            for (i in 0..2) {
                val frac = i / 2f
                val y = top + h * (1f - frac)
                drawLine(gridColor, Offset(left, y), Offset(left + w, y), strokeWidth = 1f)
                drawText(measurer, Fmt.num(maxBar * frac, 1), Offset(2.dp.toPx(), y - 6.dp.toPx()), labelStyle)
            }
            // Barras
            val slot = w / bars.size
            val barW = slot * 0.7f
            bars.forEachIndexed { i, b ->
                val bh = (b.value / maxBar).toFloat().coerceIn(0f, 1f) * h
                val x = left + slot * i + (slot - barW) / 2f
                val c = b.color ?: barColor
                drawRect(
                    color = if (selected == null || selected == i) c else c.copy(alpha = 0.35f),
                    topLeft = Offset(x, top + h - bh),
                    size = Size(barW, bh)
                )
            }
            // Línea secundaria
            if (hasLine && line != null) {
                val maxLine = if (sharedAxis) maxBar else niceMax(line.filterNotNull().maxOrNull() ?: 1.0)
                val path = Path()
                var started = false
                line.forEachIndexed { i, v ->
                    if (v == null) { started = false; return@forEachIndexed }
                    val x = left + slot * i + slot / 2f
                    val y = top + h * (1f - (v / maxLine).toFloat().coerceIn(0f, 1f))
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                if (!sharedAxis) for (i in 0..2) {
                    val frac = i / 2f
                    val y = top + h * (1f - frac)
                    drawText(
                        measurer, Fmt.num(maxLine * frac, lineDecimals),
                        Offset(left + w + 4.dp.toPx(), y - 6.dp.toPx()),
                        TextStyle(fontSize = 10.sp, color = lineColor)
                    )
                }
            }
            // Etiquetas del eje X
            val step = max(1, ceil(bars.size / 6.0).toInt())
            bars.forEachIndexed { i, b ->
                if (i % step == 0) {
                    drawText(measurer, b.label, Offset(left + slot * i, top + h + 4.dp.toPx()), labelStyle)
                }
            }
        }
        if (hasLine) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendDot(barColor, barLegend ?: "Consumo ($barUnit)")
                LegendDot(lineColor, lineLegend ?: "Precio ($lineUnit)")
            }
        }
    }
}

@Composable
fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).semantics { }) {
            Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        }
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

/** Línea con área rellena (evolución de precios, etc.). */
@Composable
fun LineChartView(
    labels: List<String>,
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    unit: String = "€/kWh",
    decimals: Int = 3,
    height: Dp = 180.dp
) {
    if (values.size < 2) return
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = axisColor)
    Column(modifier.fillMaxWidth()) {
        val sel = selected
        val tip = if (sel != null && sel in values.indices)
            "${labels.getOrNull(sel).orEmpty()}: ${Fmt.num(values[sel], decimals)} $unit"
        else "Toca el gráfico para ver el valor"
        Text(tip, style = MaterialTheme.typography.labelMedium, color = axisColor)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "Gráfico de línea con ${values.size} puntos" }
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        val left = 44.dp.toPx()
                        val w = size.width - left - 8.dp.toPx()
                        if (w > 0f) {
                            val idx = Math.round((offset.x - left) / w * (values.size - 1))
                            selected = if (idx in values.indices) idx else null
                        }
                    }
                }
        ) {
            val left = 44.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = 22.dp.toPx()
            val w = size.width - left - 8.dp.toPx()
            val h = size.height - top - bottom
            if (w <= 0f || h <= 0f) return@Canvas
            val minV = values.min()
            val maxV = values.max()
            val span = if (maxV - minV < 1e-9) 1.0 else maxV - minV
            val lo = minV - span * 0.05
            val hi = maxV + span * 0.05
            for (i in 0..2) {
                val frac = i / 2f
                val y = top + h * (1f - frac)
                drawLine(gridColor, Offset(left, y), Offset(left + w, y), strokeWidth = 1f)
                drawText(measurer, Fmt.num(lo + (hi - lo) * frac, decimals), Offset(2.dp.toPx(), y - 6.dp.toPx()), labelStyle)
            }
            fun px(i: Int) = left + w * i / (values.size - 1)
            fun py(v: Double) = top + h * (1f - ((v - lo) / (hi - lo)).toFloat())
            val line = Path()
            val area = Path()
            values.forEachIndexed { i, v ->
                if (i == 0) { line.moveTo(px(i), py(v)); area.moveTo(px(i), top + h); area.lineTo(px(i), py(v)) }
                else { line.lineTo(px(i), py(v)); area.lineTo(px(i), py(v)) }
            }
            area.lineTo(px(values.size - 1), top + h)
            area.close()
            drawPath(area, color.copy(alpha = 0.15f))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            selected?.let { s ->
                if (s in values.indices) drawCircle(color, 5.dp.toPx(), Offset(px(s), py(values[s])))
            }
            val step = max(1, ceil(labels.size / 5.0).toInt())
            labels.forEachIndexed { i, l ->
                if (i % step == 0 && i < values.size) drawText(measurer, l, Offset(px(i) - 12.dp.toPx(), top + h + 4.dp.toPx()), labelStyle)
            }
        }
    }
}

/** Anillo con leyenda (reparto por periodos tarifarios). */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    centerText: String,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp
) {
    val total = slices.sumOf { it.value }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.size(size).semantics { contentDescription = "Reparto del consumo por periodos" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val stroke = 22.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                var start = -90f
                if (total > 0.0) {
                    slices.forEach { s ->
                        val sweep = (s.value / total * 360.0).toFloat()
                        if (sweep > 0f) drawArc(s.color, start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                        start += sweep
                    }
                }
            }
            Text(centerText, style = MaterialTheme.typography.titleMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEach { s ->
                val pct = if (total > 0) s.value / total * 100 else 0.0
                LegendDot(s.color, "${s.label}: ${Fmt.kwh1(s.value)} (${Fmt.pct(pct)})")
            }
        }
    }
}
