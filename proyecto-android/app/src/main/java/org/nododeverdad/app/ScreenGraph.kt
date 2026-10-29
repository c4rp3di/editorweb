package org.nododeverdad.app

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** «Rastro»: apps → empresas → país de la sede. Con alternativa en lista para lectores de pantalla. */
class ScreenGraph(private val act: MainActivity) : Screen {

    private val root = act.vbox(16)
    override val view: View = root

    private var listMode = false
    private var infoBox: LinearLayout? = null

    override fun refresh() {
        root.removeAllViews()
        val days = Prefs.range(act)
        val st = Store.get(act)
        val edges = st.edges(days)

        root.addView(act.text("Rastro", 22f, act.col(R.attr.nvText), true))
        root.addView(act.muted("Sigue el camino: de tus apps a las empresas y a su país.", 13f), act.lp(bottom = 10))

        if (edges.isEmpty()) {
            val c = act.card(16)
            c.addView(act.h2("Aún no hay nada que dibujar"))
            c.addView(act.text("Enciende el radar y usa el móvil un rato, o mira un ejemplo.", 15f), act.lp(top = 6))
            if (!st.hasDemo()) c.addView(act.button("Ver un ejemplo", false) { act.loadDemo() }, act.lp(top = 12))
            root.addView(c)
            return
        }

        // ----- Selección de nodos: top 8 apps, hasta 16 empresas -----
        val compsPerApp = HashMap<String, HashSet<String>>()
        for (e in edges) compsPerApp.getOrPut(e.pkg) { HashSet() }.add(e.company)
        val appOrder = ArrayList<String>(compsPerApp.keys)
        appOrder.sortByDescending { compsPerApp[it]?.size ?: 0 }
        val apps = appOrder.take(8)
        val appSet = HashSet<String>(apps)

        val totalPerCompany = HashMap<String, Int>()
        for (e in edges) if (appSet.contains(e.pkg)) totalPerCompany[e.company] = (totalPerCompany[e.company] ?: 0) + e.n
        val compOrder = ArrayList<String>(totalPerCompany.keys)
        compOrder.sortByDescending { totalPerCompany[it] ?: 0 }
        val comps = compOrder.take(16)
        val compSet = HashSet<String>(comps)
        var maxN = 1
        for (id in comps) maxN = Math.max(maxN, totalPerCompany[id] ?: 1)

        val nodes = ArrayList<GraphView.GNode>()
        val gedges = ArrayList<GraphView.GEdge>()
        for (p in apps) {
            val n = compsPerApp[p]?.size ?: 1
            nodes.add(GraphView.GNode("a:$p", 0, AppInfo.label(act, p), act.col(R.attr.nvAccent), Math.min(1f, n / 8f)))
        }
        val countryCompanies = HashMap<String, ArrayList<String>>()
        for (id in comps) {
            val co = Knowledge.byId(id) ?: continue
            val w = (Math.log(1.0 + (totalPerCompany[id] ?: 1)) / Math.log(1.0 + maxN)).toFloat()
            nodes.add(GraphView.GNode("k:$id", 1, co.name, act.catColor(co.cat), w))
            countryCompanies.getOrPut(co.country) { ArrayList() }.add(id)
        }
        for ((country, ids) in countryCompanies) {
            val eea = Knowledge.byId(ids[0])?.eea ?: false
            val color = if (eea) act.col(R.attr.nvPush) else act.col(R.attr.nvAtr)
            nodes.add(GraphView.GNode("c:$country", 2, country, color, Math.min(1f, ids.size / 6f)))
            for (id in ids) gedges.add(GraphView.GEdge("k:$id", "c:$country", 0.4f, true))
        }
        for (e in edges) {
            if (!appSet.contains(e.pkg) || !compSet.contains(e.company)) continue
            val w = (Math.log(1.0 + e.n) / Math.log(1.0 + maxN)).toFloat()
            gedges.add(GraphView.GEdge("a:${e.pkg}", "k:${e.company}", w, false))
        }

        root.addView(act.segmented(listOf("Grafo", "Lista"), if (listMode) 1 else 0) {
            listMode = (it == 1)
            refresh()
        })

        val holder = FrameLayout(act)
        holder.background = act.shape(act.col(R.attr.nvSurface), 20, act.col(R.attr.nvLine), 1)
        root.addView(holder, act.lp(h = 0, weight = 1f, top = 10))

        val info = act.vbox(12)
        infoBox = info
        info.minimumHeight = act.dp(84)

        if (!listMode) {
            val g = GraphView(act)
            g.contentDescription = "Grafo de ${apps.size} apps, ${comps.size} empresas y ${countryCompanies.size} países. Usa la vista Lista para leerlo como texto."
            g.setGraph(nodes, gedges)
            g.onSelect = { n -> showInfo(n) }
            holder.addView(g, FrameLayout.LayoutParams(MATCH, MATCH))

            val legend = act.muted("● apps  ● empresas (color = qué hacen)  ● país: verde dentro del EEE, ámbar fuera. Pellizca para ampliar; doble toque para centrar.", 11f)
            root.addView(legend, act.lp(top = 8))
            info.addView(act.muted("Toca un punto para ver su ficha.", 13f))
        } else {
            val sv = ScrollView(act)
            val lb = act.vbox(16)
            for (p in apps) {
                lb.addView(act.text(AppInfo.label(act, p), 16f, act.col(R.attr.nvText), true), act.lp(top = 10))
                val names = ArrayList<String>()
                for (e in edges) {
                    if (e.pkg != p || !compSet.contains(e.company)) continue
                    val co = Knowledge.byId(e.company) ?: continue
                    names.add(co.name + " (" + co.country + ")")
                }
                lb.addView(act.text("→ " + names.joinToString(", "), 14f, act.col(R.attr.nvMuted)), act.lp(top = 2))
                lb.addView(act.button("Ver ficha de la app", false) { act.openApp(p) }, act.lp(top = 6))
            }
            sv.addView(lb)
            holder.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
            info.addView(act.muted("Vista de texto: equivale al grafo.", 13f))
        }
        root.addView(info, act.lp(top = 10))
    }

    private fun showInfo(n: GraphView.GNode) {
        val info = infoBox ?: return
        info.removeAllViews()
        info.background = act.shape(act.col(R.attr.nvSurface), 16, act.col(R.attr.nvLine), 1)
        when (n.kind) {
            0 -> {
                val pkg = n.key.substring(2)
                info.addView(act.text(n.label, 16f, act.col(R.attr.nvText), true))
                info.addView(act.button("Ver ficha de la app", false) { act.openApp(pkg) }, act.lp(top = 8))
            }
            1 -> {
                val id = n.key.substring(2)
                val co = Knowledge.byId(id)
                info.addView(act.text(n.label, 16f, act.col(R.attr.nvText), true))
                if (co != null) info.addView(act.muted(co.cat.plain + " · Sede: " + co.country, 13f))
                info.addView(act.button("Ver ficha de la empresa", false) { act.showCompany(id) }, act.lp(top = 8))
            }
            else -> {
                val country = n.key.substring(2)
                val names = ArrayList<String>()
                for (co in Knowledge.all) if (co.country == country) names.add(co.name)
                info.addView(act.text(country, 16f, act.col(R.attr.nvText), true))
                info.addView(act.muted("Empresas de la base con sede aquí: " + names.size, 13f))
            }
        }
    }
}
