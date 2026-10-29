package org.nododeverdad.app

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «Cambios»: altas y bajas en tu móvil y cronología pública de operaciones y resoluciones. */
class ScreenTime(private val act: MainActivity) : Screen {

    private val root = act.vbox(16)
    private val content = act.vbox()
    private var tab = 0
    private var onlyMine = false
    private var built = false

    override val view: View = root

    private fun fmtDate(ts: Long): String = SimpleDateFormat("d MMM", Locale("es", "ES")).format(Date(ts))

    override fun refresh() {
        if (!built) {
            built = true
            root.addView(act.text("Cambios", 22f, act.col(R.attr.nvText), true))
            root.addView(act.muted("Altas y bajas: lo que aparece y lo que desaparece. No solo la foto, también la película.", 13f), act.lp(bottom = 10))
            root.addView(act.segmented(listOf("Altas", "Bajas", "Historia"), tab) {
                tab = it
                fill()
            })
            val sv = ScrollView(act)
            sv.addView(content)
            root.addView(sv, act.lp(h = 0, weight = 1f, top = 10))
        }
        fill()
    }

    private fun fill() {
        content.removeAllViews()
        val st = Store.get(act)
        val life = st.lifetimeByCompany()
        val now = System.currentTimeMillis()
        val week = 7L * 86400000L
        val first = st.firstTs()

        when (tab) {
            0 -> {
                content.addView(act.muted("Empresas vistas por primera vez en tu móvil en los últimos 7 días.", 13f))
                if (life.isEmpty()) {
                    content.addView(empty("Aún no hay historial."), act.lp(top = 12))
                    return
                }
                if (first > 0 && now - first < 3L * 86400000L) {
                    content.addView(act.notBox("Llevas pocos días con el radar: al principio casi todo es «nuevo». Vuelve en una semana para ver altas reales."), act.lp(top = 10))
                }
                var n = 0
                for (c in life) {
                    if (c.first < now - week) continue
                    val co = Knowledge.byId(c.id) ?: continue
                    val d = ((now - c.first) / 86400000L).toInt()
                    val sub = (if (d <= 0) "Nueva hoy" else "Nueva hace $d " + (if (d == 1) "día" else "días")) + " · " + co.cat.label
                    content.addView(act.companyRow(co, sub, fmt(c.n)) { act.showCompany(co.id) })
                    n++
                }
                if (n == 0) content.addView(empty("Nada nuevo esta semana."), act.lp(top = 12))
            }
            1 -> {
                content.addView(act.muted("Empresas que aparecían y llevan más de 7 días sin dar señales.", 13f))
                var n = 0
                for (c in life) {
                    if (c.last >= now - week) continue
                    val co = Knowledge.byId(c.id) ?: continue
                    val d = ((now - c.last) / 86400000L).toInt()
                    val sub = "Sin señales desde hace $d días (última: " + fmtDate(c.last) + ")"
                    content.addView(act.companyRow(co, sub, null) { act.showCompany(co.id) })
                    n++
                }
                if (n == 0) content.addView(empty("Ninguna baja por ahora."), act.lp(top = 12))
                else content.addView(act.notBox("Que una empresa deje de aparecer no implica que desaparezca: puede que ya no uses la app que hablaba con ella, o que haya cambiado de dominio o de dueño."), act.lp(top = 12))
            }
            else -> {
                content.addView(act.muted("Operaciones corporativas y resoluciones públicas que afectan a empresas de la base.", 13f))
                val seenIds = HashSet<String>()
                for (c in life) seenIds.add(c.id)
                val sw = SwitchCompat(act)
                sw.text = "Solo las que afectan a mi móvil"
                sw.setTextColor(act.col(R.attr.nvText))
                sw.textSize = 15f * Prefs.scale(act)
                sw.isChecked = onlyMine
                sw.setOnCheckedChangeListener { _, v ->
                    onlyMine = v
                    fill()
                }
                content.addView(sw, act.lp(top = 10))

                val list = ArrayList<Event>(Knowledge.events)
                list.sortByDescending { it.year }
                for (e in list) {
                    var mine = false
                    for (id in e.companyIds) if (seenIds.contains(id)) mine = true
                    if (onlyMine && !mine) continue
                    val c = act.card(14)
                    val head = act.hbox()
                    head.addView(act.pill(e.year.toString(), act.col(R.attr.nvAna)))
                    val kindColor = if (e.kind == 0) act.col(R.attr.nvSoc) else act.col(R.attr.nvPub)
                    head.addView(act.pill(if (e.kind == 0) "Operación corporativa" else "Resolución de autoridad", kindColor, 11f), act.lp(w = WRAP, start = 8))
                    if (mine) head.addView(act.pill("En tu móvil", act.col(R.attr.nvAccent), 11f), act.lp(w = WRAP, start = 8))
                    c.addView(head)
                    c.addView(act.text(e.title, 16f, act.col(R.attr.nvText), true), act.lp(top = 8))
                    c.addView(act.text(e.detail, 14f, act.col(R.attr.nvMuted)), act.lp(top = 4))
                    val chips = act.hbox()
                    for (id in e.companyIds) {
                        val co = Knowledge.byId(id) ?: continue
                        val p = act.pill(co.name, act.catColor(co.cat), 12f)
                        p.isClickable = true
                        p.setOnClickListener { act.showCompany(co.id) }
                        chips.addView(p, act.lp(w = WRAP, end = 6))
                    }
                    c.addView(chips, act.lp(top = 8))
                    content.addView(c, act.lp(top = 10))
                }
                content.addView(act.notBox("Una sanción administrativa no es una condena penal y puede estar recurrida. Consulta el estado actual en la web de cada autoridad. La lista es una selección, no un registro completo."), act.lp(top = 14))
            }
        }
    }

    private fun empty(msg: String): View {
        val c = act.card(16)
        c.addView(act.text(msg, 15f))
        return c
    }
}

@Suppress("unused")
private fun unusedLinearT(l: LinearLayout) = l
