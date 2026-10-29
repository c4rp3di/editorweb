package org.nododeverdad.app

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Pantalla principal: el radar. Lo primero que ve la persona y lo que quiere compartir. */
class ScreenRadar(private val act: MainActivity) : Screen {

    private val scroll = ScrollView(act)
    private val box = act.vbox(16)
    private var sig = ""

    override val view: View = scroll

    init {
        scroll.isFillViewport = true
        scroll.addView(box)
    }

    override fun refresh() {
        val days = Prefs.range(act)
        val st = Store.get(act)
        val sum = st.summary(days)
        val demo = st.hasDemo()
        val key = "$days|${sum.queries}|${sum.hits}|${sum.companies}|${sum.blocked}|${RadarVpnService.running}|$demo|${RadarVpnService.lastError}|${Prefs.profile(act)}"
        if (key == sig) return
        sig = key
        build(days, sum, demo)
    }

    private fun privateDnsStrict(): Boolean {
        return try {
            android.provider.Settings.Global.getString(act.contentResolver, "private_dns_mode") == "hostname"
        } catch (e: Exception) {
            false
        }
    }

    fun forceRebuild() {
        sig = ""
        refresh()
    }

    private fun build(days: Int, sum: Summary, demo: Boolean) {
        val st = Store.get(act)
        box.removeAllViews()

        // ----- Cabecera -----
        val top = act.hbox()
        val title = act.text("Nodo de Verdad", 22f, act.col(R.attr.nvText), true)
        top.addView(title, act.lp(w = 0, weight = 1f))
        top.addView(act.iconButton(R.drawable.ic_settings, "Ajustes") { act.showSettings() })
        box.addView(top)
        box.addView(act.muted("Quién recibe señales de tu móvil… y quién hay detrás.", 13f), act.lp(bottom = 12))

        // ----- Tarjeta principal -----
        val running = RadarVpnService.running
        val hero = act.card(16)
        val rangeIdx = when (days) {
            1 -> 0
            30 -> 2
            else -> 1
        }
        hero.addView(act.segmented(listOf("Hoy", "7 días", "30 días"), rangeIdx) {
            val d = if (it == 0) 1 else if (it == 2) 30 else 7
            Prefs.setRange(act, d)
            act.refreshAll()
        })

        val statusColor = if (running) act.col(R.attr.nvAccent) else if (demo) act.col(R.attr.nvAtr) else act.col(R.attr.nvMuted)
        val statusText = if (running) "● RADAR ENCENDIDO" else if (demo) "EJEMPLO · datos inventados" else "Radar apagado"
        val status = act.pill(statusText, statusColor, 12f)
        hero.addView(status, act.lp(w = WRAP, top = 12))

        val rv = RadarView(act)
        rv.active = running
        val comps = st.byCompany(days)
        var maxN = 1
        for (c in comps) if (c.n > maxN) maxN = c.n
        val blips = ArrayList<RadarView.Blip>()
        for (c in comps.take(48)) {
            val co = Knowledge.byId(c.id) ?: continue
            val ang = ((c.id.hashCode() and 0x7fffffff) % 360).toFloat()
            val frac = c.n.toFloat() / maxN
            blips.add(RadarView.Blip(ang, 0.92f - 0.62f * frac, act.catColor(co.cat), 0.8f + 0.9f * frac))
        }
        rv.blips = blips
        rv.contentDescription = "Radar con " + sum.companies + " empresas de seguimiento detectadas"
        hero.addView(rv, act.lp(h = act.dp(230), top = 8))

        val big = act.text(fmt(sum.companies), 60f, act.col(R.attr.nvAccent), true)
        big.gravity = Gravity.CENTER
        hero.addView(big)
        val rangeTxt = if (days <= 1) "hoy" else "en $days días"
        val cap = act.text(
            if (sum.companies == 1) "empresa de seguimiento ha recibido señales de tu móvil $rangeTxt"
            else "empresas de seguimiento han recibido señales de tu móvil $rangeTxt",
            15f, act.col(R.attr.nvText)
        )
        cap.gravity = Gravity.CENTER
        hero.addView(cap, act.lp(top = 2))

        val err = RadarVpnService.lastError
        if (err != null && !running) {
            hero.addView(act.text(err, 13f, act.col(R.attr.nvAccent2), true), act.lp(top = 10))
        }

        if (running && privateDnsStrict()) {
            hero.addView(act.text("Aviso: tienes «DNS privado» en modo estricto. Esas consultas no pasan por el radar. Ponlo en Automático en Ajustes → Red.", 13f, act.col(R.attr.nvAtr), true), act.lp(top = 10))
        }

        if (running) {
            hero.addView(act.button("Apagar radar", false) { act.toggleRadar() }, act.lp(top = 16))
        } else {
            hero.addView(act.button("Encender radar", true) { act.toggleRadar() }, act.lp(top = 16))
            if (demo) {
                hero.addView(act.button("Quitar datos de ejemplo", false) { act.clearDemo() }, act.lp(top = 8))
            } else if (sum.companies == 0) {
                hero.addView(act.button("Ver un ejemplo antes", false) { act.loadDemo() }, act.lp(top = 8))
            }
        }
        box.addView(hero)

        // ----- Sin datos todavía -----
        if (sum.companies == 0) {
            val how = act.card(16)
            how.addView(act.h2(if (running) "Escuchando…" else "Cómo funciona"))
            val msg = if (running)
                "Usa tu móvil con normalidad unos minutos. Cuando una app llame a un rastreador conocido, aparecerá aquí."
            else
                "1. Enciendes el radar (una VPN local, solo para consultas DNS).\n2. Usas tu móvil como siempre.\n3. Aquí ves quién recibe señales y quién hay detrás de cada empresa."
            how.addView(act.text(msg, 15f), act.lp(top = 8))
            how.addView(act.divider(), act.lp(top = 12, bottom = 12))
            how.addView(act.text("Lo que garantiza:", 14f, act.col(R.attr.nvMuted), true))
            how.addView(act.text("• Nada sale de tu móvil.\n• No se guarda tu historial de navegación.\n• No se lee el contenido de nada.\n• Sin cuentas, sin anuncios, sin analítica.", 14f), act.lp(top = 4))
            box.addView(how, act.lp(top = 14))
            return
        }

        // ----- Índice -----
        var ads = 0
        var outside = 0
        for (c in comps) {
            val co = Knowledge.byId(c.id) ?: continue
            if (co.cat == Cat.PUB) ads++
            if (!co.eea) outside++
        }
        val score = Score.of(sum.companies, ads)
        val sc = act.card(16)
        val srow = act.hbox()
        srow.addView(act.text("Índice del radar", 15f, act.col(R.attr.nvMuted), true), act.lp(w = 0, weight = 1f))
        srow.addView(act.text("$score/100 · " + Score.label(score), 16f, act.col(R.attr.nvPub), true))
        sc.addView(srow)
        sc.addView(act.bar(score / 100f, act.col(R.attr.nvPub), 10), act.lp(top = 10))
        sc.addView(act.muted("$ads empresas de publicidad · $outside de ${sum.companies} con sede fuera del EEE. Es un dato orientativo: mide cuánta gente recibe señales de tu móvil, no el peligro.", 12f), act.lp(top = 10))
        box.addView(sc, act.lp(top = 14))

        // ----- Cifras -----
        val tiles = act.hbox()
        tiles.addView(act.stat(fmt(sum.queries), "consultas vigiladas", act.col(R.attr.nvText)), act.lp(w = 0, weight = 1f, end = 6))
        tiles.addView(act.stat(fmt(sum.hits), "a rastreadores", act.col(R.attr.nvAna)), act.lp(w = 0, weight = 1f, end = 6))
        if (RadarVpnService.shield || sum.blocked > 0) {
            tiles.addView(act.stat(fmt(sum.blocked), "bloqueadas", act.col(R.attr.nvPush)), act.lp(w = 0, weight = 1f))
        } else {
            tiles.addView(act.stat(fmt(sum.apps), "apps implicadas", act.col(R.attr.nvAtr)), act.lp(w = 0, weight = 1f))
        }
        box.addView(tiles, act.lp(top = 12))

        // ----- Categorías -----
        val counts = st.catCounts(days)
        val catCard = act.card(16)
        catCard.addView(act.h2("Qué hacen con tus datos"))
        val sb = StackBar(act)
        val vals = ArrayList<Float>()
        val cols = ArrayList<Int>()
        for (k in Cat.values()) {
            val n = counts[k.key] ?: 0
            if (n > 0) {
                vals.add(n.toFloat())
                cols.add(act.catColor(k))
            }
        }
        sb.set(vals, cols)
        sb.layoutParams = LinearLayout.LayoutParams(MATCH, act.dp(14))
        catCard.addView(sb, act.lp(top = 10, bottom = 8, h = act.dp(14)))
        for (k in Cat.values()) {
            val n = counts[k.key] ?: 0
            if (n == 0) continue
            val r = act.hbox()
            r.addView(act.pill(k.label, act.catColor(k)))
            r.addView(act.text(k.plain, 13f, act.col(R.attr.nvMuted)), act.lp(w = 0, weight = 1f, start = 10))
            r.addView(act.text(n.toString(), 15f, act.col(R.attr.nvText), true))
            catCard.addView(r, act.lp(top = 8))
        }
        box.addView(catCard, act.lp(top = 12))

        // ----- Apps más habladoras -----
        val apps = st.byApp(days)
        val appCard = act.card(12)
        appCard.addView(act.h2("Las apps más habladoras"), act.lp(start = 4, top = 4))
        for (a in apps.take(5)) {
            val sub = a.companies.toString() + (if (a.companies == 1) " empresa" else " empresas")
            appCard.addView(act.appRow(a.pkg, sub, fmt(a.n)) { act.openApp(a.pkg) })
        }
        box.addView(appCard, act.lp(top = 12))

        // ----- Empresas que más aparecen -----
        val coCard = act.card(12)
        coCard.addView(act.h2("Quién recibe más señales"), act.lp(start = 4, top = 4))
        var shown = 0
        for (c in comps) {
            if (shown >= 6) break
            val co = Knowledge.byId(c.id) ?: continue
            val sub = co.cat.label + " · " + co.country
            coCard.addView(act.companyRow(co, sub, fmt(c.n)) { act.showCompany(co.id) })
            shown++
        }
        box.addView(coCard, act.lp(top = 12))

        // ----- Compartir -----
        box.addView(act.button("Compartir mi radar", true) { act.shareRadar() }, act.lp(top = 16))
        box.addView(
            act.muted("La tarjeta que compartes muestra números y empresas, nunca los nombres de tus apps.", 12f),
            act.lp(top = 8)
        )
        box.addView(act.space(8))
    }
}

/** Marcador para cuadrar el import de FrameLayout si se amplía la pantalla. */
@Suppress("unused")
private fun unusedFrame(f: FrameLayout) = f
