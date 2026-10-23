package com.luzcontrol.app.data.export

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.luzcontrol.app.data.local.AppDatabase
import com.luzcontrol.app.data.mapper.toDomain
import com.luzcontrol.app.domain.model.ExportedFile
import com.luzcontrol.app.domain.model.PricePoint
import com.luzcontrol.app.domain.model.TariffCalendar
import com.luzcontrol.app.domain.repository.ExportRepository
import com.luzcontrol.app.domain.repository.SettingsRepository
import com.luzcontrol.app.domain.usecase.CalculateCostUseCase
import com.luzcontrol.app.domain.usecase.SimulateBillUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val cost: CalculateCostUseCase,
    private val bill: SimulateBillUseCase,
    private val settings: SettingsRepository
) : ExportRepository {

    private val es = Locale.forLanguageTag("es-ES")
    private val zone = TariffCalendar.ZONE

    private fun exportDir(): File {
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return dir
    }

    private fun uriFor(file: File): String =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file).toString()

    private fun short(cups: String) = cups.takeLast(6)

    override suspend fun exportCsv(cups: String): Result<ExportedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val consumption = db.consumptionDao().listAll(cups).map { it.toDomain() }
            val prices = db.priceDao().listAll().map { it.toDomain() }.associateBy { it.start }
            val name = "consumo_${short(cups)}_${LocalDate.now(zone)}.csv"
            val file = File(exportDir(), name)
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            file.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("﻿") // BOM: Excel reconoce UTF-8
                w.write("cups;fecha_hora;periodo;kwh;excedente_kwh;precio_pvpc_eur_kwh;coste_eur;lectura\n")
                for (c in consumption) {
                    val price: PricePoint? = prices[c.start.truncatedTo(ChronoUnit.HOURS)]
                    val z = c.start.atZone(zone)
                    w.write(
                        listOf(
                            cups,
                            z.format(fmt),
                            TariffCalendar.periodAt(c.start).label,
                            String.format(es, "%.3f", c.kwh),
                            String.format(es, "%.3f", c.surplusKwh),
                            price?.let { String.format(es, "%.5f", it.pvpcEurKwh) } ?: "",
                            price?.let { String.format(es, "%.5f", it.pvpcEurKwh * c.kwh) } ?: "",
                            if (c.real) "Real" else "Estimada"
                        ).joinToString(";") + "\n"
                    )
                }
            }
            ExportedFile(uriFor(file), "text/csv", name)
        }
    }

    override suspend fun exportPdf(cups: String): Result<ExportedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val consumption = db.consumptionDao().listAll(cups).map { it.toDomain() }
            val prices = db.priceDao().listAll().map { it.toDomain() }
            val contract = db.contractDao().get(cups)?.toDomain()
            val params = settings.settings.value.billing

            val byMonth = consumption.groupBy { YearMonth.from(it.start.atZone(zone)) }.toSortedMap()
            val rows = byMonth.map { (month, points) ->
                val c = cost(points, prices)
                val sim = bill(month, points, prices, contract, params)
                listOf(
                    month.format(DateTimeFormatter.ofPattern("MM/yyyy")),
                    String.format(es, "%.1f kWh", points.sumOf { it.kwh }),
                    String.format(es, "%.2f €", c.totalEur),
                    sim?.let { String.format(es, "%.2f €", it.totalEur) } ?: "-"
                )
            }

            val name = "informe_${short(cups)}_${LocalDate.now(zone)}.pdf"
            val file = File(exportDir(), name)
            val doc = PdfDocument()
            val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
            val head = Paint().apply { textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
            val body = Paint().apply { textSize = 11f }
            val small = Paint().apply { textSize = 9f }

            var pageNumber = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            var canvas = page.canvas
            var y = 60f
            canvas.drawText("Informe de consumo eléctrico", 40f, y, title); y += 24f
            canvas.drawText("CUPS: $cups", 40f, y, body); y += 16f
            canvas.drawText("Generado: ${LocalDate.now(zone).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}", 40f, y, body); y += 28f

            val cols = floatArrayOf(40f, 150f, 280f, 410f)
            val headers = listOf("Mes", "Consumo", "Coste energía", "Factura estimada")
            headers.forEachIndexed { i, h -> canvas.drawText(h, cols[i], y, head) }
            y += 18f

            for (row in rows) {
                if (y > 780f) {
                    doc.finishPage(page)
                    pageNumber++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                    canvas = page.canvas
                    y = 60f
                }
                row.forEachIndexed { i, v -> canvas.drawText(v, cols[i], y, body) }
                y += 16f
            }
            y += 14f
            if (y > 800f) {
                doc.finishPage(page)
                pageNumber++
                page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                canvas = page.canvas
                y = 60f
            }
            canvas.drawText(
                "Los importes son estimaciones basadas en tus datos de consumo y precios PVPC;",
                40f, y, small
            ); y += 12f
            canvas.drawText("no sustituyen a la factura oficial de tu comercializadora.", 40f, y, small)
            doc.finishPage(page)
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            ExportedFile(uriFor(file), "application/pdf", name)
        }
    }
}
