package org.nododeverdad.app

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

/** «Quién»: directorio de empresas, con las que aparecen en tu móvil primero. */
class ScreenWho(private val act: MainActivity) : Screen {

    private val root = act.vbox(16)
    private val listBox = act.vbox()
    private val countLine = act.muted("", 12f)
    private var query = ""
    private var catFilter: Cat? = null
    private var built = false
    private var seen: Map<String, CompanyStat> = emptyMap()

    override val view: View = root

    override fun refresh() {
        val m = HashMap<String, CompanyStat>()
        for (c in Store.get(act).byCompany(30)) m[c.id] = c
        seen = m
        if (!built) buildFrame()
        fillList()
    }

    private fun buildFrame() {
        built = true
        root.addView(act.text("Quién", 22f, act.col(R.attr.nvText), true))
        root.addView(act.muted("Las empresas que rastrean, quién es su matriz y dónde tienen la sede.", 13f), act.lp(bottom = 10))

        val et = EditText(act)
        et.hint = "Buscar empresa o dominio"
        et.setHintTextColor(act.col(R.attr.nvMuted))
        et.setTextColor(act.col(R.attr.nvText))
        et.textSize = 16f * Prefs.scale(act)
        et.inputType = InputType.TYPE_CLASS_TEXT
        et.isSingleLine = true
        et.minHeight = act.dp(52)
        et.setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(8))
        et.background = act.shape(act.col(R.attr.nvSurface), 26, act.col(R.attr.nvLine), 1)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = (s?.toString() ?: "").trim().lowercase()
                fillList()
            }
        })
        root.addView(et)

        val hs = HorizontalScrollView(act)
        hs.isHorizontalScrollBarEnabled = false
        val chips = act.hbox()
        fun chip(label: String, cat: Cat?) {
            val c = if (cat == null) act.col(R.attr.nvAccent) else act.catColor(cat)
            val t = act.pill(label, c, 13f)
            t.minHeight = act.dp(40)
            t.gravity = android.view.Gravity.CENTER
            t.isClickable = true
            t.setOnClickListener {
                catFilter = cat
                fillList()
            }
            chips.addView(t, act.lp(w = WRAP, end = 8))
        }
        chip("Todas", null)
        for (k in Cat.values()) chip(k.label, k)
        hs.addView(chips)
        root.addView(hs, act.lp(top = 10))

        root.addView(countLine, act.lp(top = 8))

        val sv = ScrollView(act)
        sv.addView(listBox)
        root.addView(sv, act.lp(h = 0, weight = 1f, top = 4))
    }

    private fun fillList() {
        listBox.removeAllViews()
        val items = ArrayList<Company>()
        for (co in Knowledge.all) {
            if (catFilter != null && co.cat != catFilter) continue
            if (query.isNotEmpty()) {
                var ok = co.name.lowercase().contains(query) || (co.parent?.lowercase()?.contains(query) ?: false)
                if (!ok) {
                    for (t in co.domains) if (Knowledge.domainOf(t).contains(query)) ok = true
                }
                if (!ok) continue
            }
            items.add(co)
        }
        items.sortWith(Comparator { a, b ->
            val na = seen[a.id]?.n ?: -1
            val nb = seen[b.id]?.n ?: -1
            if (na != nb) nb.compareTo(na) else a.name.compareTo(b.name)
        })
        var inPhone = 0
        for (co in items) if (seen.containsKey(co.id)) inPhone++
        countLine.text = "${items.size} empresas · $inPhone en tu móvil (30 días)"
        for (co in items) {
            val s = seen[co.id]
            val sub = co.cat.label + " · " + co.country + (if (co.parent != null && co.parent != co.name) " · " + co.parent else "")
            val badge = if (s != null) fmt(s.n) else null
            listBox.addView(act.companyRow(co, sub, badge) { act.showCompany(co.id) })
        }
        if (items.isEmpty()) listBox.addView(act.muted("Nada coincide con tu búsqueda.", 14f), act.lp(top = 12))
    }
}

@Suppress("unused")
private fun unusedLinear(l: LinearLayout) = l
