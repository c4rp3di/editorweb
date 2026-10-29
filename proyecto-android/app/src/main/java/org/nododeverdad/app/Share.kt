package org.nododeverdad.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Tarjeta para compartir y exportación abierta. Nada de esto se sube a ningún sitio: lo envías tú. */
object Share {

    private fun shareDir(ctx: Context): File {
        val d = File(ctx.cacheDir, "share")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun send(ctx: Context, f: File, mime: String, message: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND)
        i.type = mime
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.putExtra(Intent.EXTRA_TEXT, message)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(i, "Compartir")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    // ---------- Tarjeta de imagen ----------

    fun card(ctx: Context, days: Int): File? {
        val st = Store.get(ctx)
        val sum = st.summary(days)
        if (sum.companies == 0) return null
        val comps = st.byCompany(days)
        var outside = 0
        var ads = 0
        for (cs in comps) {
            val co = Knowledge.byId(cs.id) ?: continue
            if (!co.eea) outside++
            if (co.cat == Cat.PUB) ads++
        }
        val score = Score.of(sum.companies, ads)

        val w = 1080
        val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#070B1A"), Color.parseColor("#101C40"), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // Anillos decorativos
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.parseColor("#2EF2C8")
        p.alpha = 60
        for (i in 1..5) c.drawCircle(900f, 200f, 90f * i, p)
        p.alpha = 255
        p.style = Paint.Style.FILL

        val accent = Color.parseColor("#2EF2C8")
        val pink = Color.parseColor("#FF4D8D")
        val white = Color.parseColor("#F2F5FF")
        val gray = Color.parseColor("#9CA9CC")

        p.color = accent
        p.textSize = 40f
        p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("NODO DE VERDAD", 80f, 120f, p)

        p.color = white
        p.textSize = 64f
        c.drawText("Mi móvil habló con", 80f, 300f, p)

        p.color = accent
        p.textSize = 330f
        c.drawText(sum.companies.toString(), 70f, 600f, p)

        p.color = white
        p.textSize = 64f
        c.drawText(if (sum.companies == 1) "empresa de seguimiento" else "empresas de seguimiento", 80f, 700f, p)
        p.color = gray
        p.typeface = Typeface.DEFAULT
        p.textSize = 40f
        val range = if (days <= 1) "hoy" else "en los últimos $days días"
        c.drawText("Detectadas $range por mi radar local", 80f, 765f, p)

        // Índice
        p.color = Color.parseColor("#1A2547")
        c.drawRoundRect(RectF(80f, 830f, 1000f, 960f), 40f, 40f, p)
        p.color = pink
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 56f
        c.drawText("Índice $score/100", 120f, 915f, p)
        p.color = white
        p.textSize = 44f
        p.textAlign = Paint.Align.RIGHT
        c.drawText(Score.label(score), 960f, 912f, p)
        p.textAlign = Paint.Align.LEFT

        // Top 3 empresas (nunca nombres de apps)
        var y = 1050f
        var shown = 0
        for (cs in comps) {
            if (shown >= 3) break
            val co = Knowledge.byId(cs.id) ?: continue
            val k = Cat.of(co.cat.key)
            p.color = if (k == Cat.PUB) pink else accent
            c.drawCircle(98f, y - 14f, 14f, p)
            p.color = white
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 44f
            val nm = if (co.name.length > 28) co.name.substring(0, 27) + "…" else co.name
            c.drawText(nm, 130f, y, p)
            p.color = gray
            p.typeface = Typeface.DEFAULT
            p.textSize = 38f
            p.textAlign = Paint.Align.RIGHT
            c.drawText(co.country, 1000f, y, p)
            p.textAlign = Paint.Align.LEFT
            y += 70f
            shown++
        }
        if (outside > 0) {
            p.color = gray
            p.textSize = 36f
            c.drawText("$outside de ${sum.companies} tienen su sede fuera del Espacio Económico Europeo", 80f, 1270f, p)
        }
        p.color = Color.parseColor("#5C6A92")
        p.textSize = 30f
        c.drawText("Aparecer aquí no implica ilegalidad. Dato orientativo de mi móvil.", 80f, 1315f, p)

        val f = File(shareDir(ctx), "mi-radar.png")
        val os = FileOutputStream(f)
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
        } finally {
            os.close()
        }
        return f
    }

    // ---------- Exportación abierta (esquema device-export 1.0) ----------

    private fun iso(ts: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ts))
    }

    fun exportJson(ctx: Context, days: Int, includeNames: Boolean): File {
        val rows = Store.get(ctx).exportRows(days)
        val byApp = LinkedHashMap<String, MutableList<Array<String>>>()
        for (r in rows) {
            val l = byApp.getOrPut(r[0]) { ArrayList() }
            l.add(r)
        }
        val root = JSONObject()
        root.put("schema_version", "1.0")
        root.put("generated_at", iso(System.currentTimeMillis()))
        val period = JSONObject()
        period.put("from", Store.dayMinus(Math.max(0, days - 1)))
        period.put("to", Store.dayOf(System.currentTimeMillis()))
        root.put("period", period)
        val dev = JSONObject()
        dev.put("os", "android")
        dev.put("os_version", Build.VERSION.RELEASE)
        root.put("device", dev)
        val lists = JSONObject()
        lists.put("nv-curada", "1")
        root.put("lists_versions", lists)

        val apps = JSONArray()
        var n = 1
        for ((pkg, list) in byApp) {
            val a = JSONObject()
            if (includeNames) {
                a.put("package", pkg)
                a.put("label", AppInfo.label(ctx, pkg))
            } else {
                a.put("package", "app-$n")
            }
            n++
            val trackers = JSONArray()
            for (r in list) {
                val co = Knowledge.byId(r[2])
                val t = JSONObject()
                t.put("domain", r[1])
                t.put("company", co?.name ?: r[2])
                t.put("company_id", "nv:" + r[2])
                t.put("category", Cat.of(r[3]).export)
                t.put("attribution_confidence", 90)
                t.put("requests", r[4].toInt())
                t.put("first_seen", iso(r[5].toLong()))
                t.put("last_seen", iso(r[6].toLong()))
                t.put("source", "nv-curada:1")
                trackers.put(t)
            }
            a.put("trackers", trackers)
            apps.put(a)
        }
        root.put("apps", apps)
        root.put("queries_sent", JSONArray())
        val red = JSONArray()
        if (!includeNames) red.put("apps:names")
        root.put("redactions", red)

        val f = File(shareDir(ctx), "nodo-de-verdad-export.json")
        f.writeText(root.toString(2))
        return f
    }
}
