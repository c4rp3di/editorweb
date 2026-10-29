package org.nododeverdad.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Hoja base ----------

fun MainActivity.openSheet(build: (LinearLayout, BottomSheetDialog) -> Unit): BottomSheetDialog {
    val d = BottomSheetDialog(this)
    val scroll = NestedScrollView(this)
    val box = vbox(20)
    scroll.addView(box)
    build(box, d)
    d.setContentView(scroll)
    d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    d.behavior.skipCollapsed = true
    d.setOnDismissListener { Speaker.stop() }
    d.show()
    return d
}

private fun MainActivity.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        toast("No se pudo abrir el navegador.")
    }
}

private fun MainActivity.openSettingsIntent(i: Intent) {
    try {
        startActivity(i)
    } catch (e: Exception) {
        toast("Tu móvil no permite abrir esta pantalla de ajustes directamente.")
    }
}

private fun fmtDate(ts: Long): String = SimpleDateFormat("d MMM yyyy", Locale("es", "ES")).format(Date(ts))

private fun daysAgo(ts: Long): Int = ((System.currentTimeMillis() - ts) / 86400000L).toInt()

// ---------- Ficha de empresa: Titular · Historia · Evidencia ----------

fun MainActivity.showCompany(id: String) {
    val co = Knowledge.byId(id) ?: return
    val st = Store.get(this)
    val stat = st.byCompany(30).firstOrNull { it.id == id }
    val apps = st.appsOfCompany(id, 30)
    val domainsSeen = st.domainsOfCompany(id, 30)
    val events = Knowledge.eventsFor(id)
    val catC = catColor(co.cat)

    openSheet { box, dlg ->
        val head = hbox()
        head.addView(initialCircle(co.name.substring(0, 1).uppercase(), catC, 24))
        val hv = vbox()
        hv.addView(text(co.name, 22f, col(R.attr.nvText), true))
        val parentTxt = if (co.parent != null && co.parent != co.name) "Matriz: ${co.parent} · " else ""
        hv.addView(muted(parentTxt + "Sede: " + co.country, 13f))
        head.addView(hv, lp(w = 0, weight = 1f, start = 14))
        box.addView(head)

        val pills = hbox()
        pills.addView(pill(co.cat.label, catC))
        val eeeC = if (co.eea) col(R.attr.nvPush) else col(R.attr.nvAtr)
        pills.addView(pill(if (co.eea) "Sede dentro del EEE" else "Sede fuera del EEE", eeeC), lp(w = WRAP, start = 8))
        box.addView(pills, lp(top = 12))

        var depth = if (Prefs.profile(this) == 1) 1 else 0
        var reg = if (Prefs.profile(this) == 2) 1 else 0
        val body = vbox()

        fun spoken(): String {
            val sb = StringBuilder()
            sb.append(co.name).append(". ").append(co.cat.plain).append(". ")
            if (stat != null) sb.append("En tu móvil: ${stat.n} consultas desde ${apps.size} aplicaciones. ")
            sb.append(if (reg == 0) co.cat.analogy15 else co.cat.plain70)
            sb.append(" Su sede está en ").append(co.country).append(". ")
            sb.append("Aparecer aquí no implica ilegalidad.")
            return sb.toString()
        }

        fun render() {
            body.removeAllViews()
            when (depth) {
                0 -> {
                    val headline = if (stat != null)
                        "Tu móvil habló con ${co.name}: ${fmt(stat.n)} consultas desde ${apps.size} " + (if (apps.size == 1) "app" else "apps") + " en 30 días."
                    else
                        "No hemos visto a ${co.name} en tu móvil en los últimos 30 días."
                    body.addView(text(headline, 20f, col(R.attr.nvText), true), lp(top = 16))
                    body.addView(text(co.cat.plain + ".", 16f, col(R.attr.nvMuted)), lp(top = 8))
                    body.addView(text(co.what, 15f), lp(top = 12))
                }
                1 -> {
                    body.addView(segmented(listOf("Como si tuviera 15", "Como si tuviera 70"), reg) { reg = it; render() }, lp(top = 14))
                    body.addView(text(co.what, 15f), lp(top = 14))
                    if (co.parent != null && co.parent != co.name) {
                        body.addView(text("Forma parte de ${co.parent}.", 15f), lp(top = 8))
                    }
                    body.addView(text(if (reg == 0) co.cat.analogy15 else co.cat.plain70, 15f, col(R.attr.nvText)), lp(top = 8))
                    val loc = if (co.eea)
                        "Su sede está en ${co.country}, dentro del Espacio Económico Europeo (EEE), donde rige el RGPD."
                    else
                        "Su sede está en ${co.country}, fuera del Espacio Económico Europeo (EEE). Sus datos pueden regirse por otras leyes, aunque muchas empresas se someten también al RGPD cuando tratan datos de europeos."
                    body.addView(text(loc, 15f), lp(top = 8))
                    if (apps.isNotEmpty()) {
                        body.addView(h2("Apps de tu móvil que hablan con ella"), lp(top = 18))
                        for (a in apps.take(8)) {
                            body.addView(appRow(a.pkg, fmt(a.n) + " consultas", null) { dlg.dismiss(); openApp(a.pkg) })
                        }
                    }
                    body.addView(notBox("Que una empresa reciba señales de tu móvil no significa que haga nada ilegal: la publicidad y la analítica son legales. Si la app es de la propia empresa, es comunicación propia. Lo importante es saber quién es y decidir con información."), lp(top = 16))
                    body.addView(h2("Qué puedes hacer"), lp(top = 18))
                    body.addView(text("• Revisa los permisos de las apps que más hablan con ella.\n• Restablece tu ID de publicidad en Ajustes → Privacidad → Anuncios.\n• Valora si necesitas esa app o si existe una alternativa más respetuosa.", 15f), lp(top = 6))
                }
                else -> {
                    body.addView(h2("Lo visto en tu móvil (30 días)"), lp(top = 16))
                    if (domainsSeen.isEmpty()) {
                        body.addView(muted("Nada todavía.", 14f), lp(top = 4))
                    }
                    for (d in domainsSeen) {
                        val r = vbox()
                        r.addView(text(d.domain, 14f, col(R.attr.nvText), true))
                        r.addView(muted(Cat.of(d.cat).label + " · " + fmt(d.n) + " consultas · última vez " + fmtDate(d.last), 12f))
                        body.addView(r, lp(top = 8))
                    }
                    body.addView(h2("Dominios que la base asocia a esta empresa"), lp(top = 18))
                    val sb = StringBuilder()
                    for (tkn in co.domains) {
                        sb.append("• ").append(Knowledge.domainOf(tkn)).append("  (").append(Knowledge.catOf(tkn, co).label).append(")\n")
                    }
                    body.addView(text(sb.toString().trim(), 13f), lp(top = 6))

                    body.addView(h2("Hechos públicos"), lp(top = 18))
                    if (events.isEmpty()) {
                        body.addView(muted("No incluimos operaciones corporativas ni resoluciones públicas sobre esta empresa en la lista actual. Eso no significa que no existan.", 14f), lp(top = 4))
                    }
                    for (e in events) {
                        val kind = if (e.kind == 0) "Operación corporativa" else "Resolución de una autoridad"
                        val r = vbox()
                        r.addView(text("${e.year} · ${e.title}", 14f, col(R.attr.nvText), true))
                        r.addView(muted(kind, 12f))
                        r.addView(text(e.detail, 13f), lp(top = 2))
                        body.addView(r, lp(top = 10))
                    }
                    if (events.any { it.kind == 1 }) {
                        body.addView(muted("Una sanción administrativa no es una condena penal y puede estar recurrida. Consulta su estado actual en la web de la autoridad que la emitió.", 12f), lp(top = 8))
                    }

                    body.addView(h2("Método y límites"), lp(top = 18))
                    body.addView(text("Atribución por coincidencia del dominio con una lista curada incluida en la app (v1; confianza orientativa 90/100). Puede haber dominios compartidos, cambios de propiedad posteriores u omisiones. Esta lista no sustituye a una fuente oficial.", 13f), lp(top = 6))

                    body.addView(h2("Verifícalo tú mismo"), lp(top = 18))
                    val enc = Uri.encode(co.name.replace(Regex("\\(.*\\)"), "").trim())
                    body.addView(button("Buscar en OpenCorporates", false) { openUrl("https://opencorporates.com/companies?q=$enc") }, lp(top = 8))
                    body.addView(button("Buscar su código LEI (GLEIF)", false) { openUrl("https://search.gleif.org/") }, lp(top = 8))
                    body.addView(muted("Correcciones y derecho de réplica: ver «Acerca de» en Ajustes.", 12f), lp(top = 10))
                }
            }
        }

        box.addView(segmented(listOf("Titular", "Historia", "Evidencia"), depth) { depth = it; render() }, lp(top = 16))
        box.addView(body)
        render()

        box.addView(button("🔊 Escuchar", false) { Speaker.speak(this, spoken()) }, lp(top = 20))
    }
}

// ---------- Ficha de app ----------

fun MainActivity.showApp(pkg: String) {
    val st = Store.get(this)
    val days = Prefs.range(this)
    val comps = st.companiesOfApp(pkg, days)
    var total = 0
    for (c in comps) total += c.n
    var ads = 0
    var outside = 0
    for (c in comps) {
        val co = Knowledge.byId(c.id) ?: continue
        if (co.cat == Cat.PUB) ads++
        if (!co.eea) outside++
    }
    val label = AppInfo.label(this, pkg)

    openSheet { box, dlg ->
        val head = hbox()
        head.addView(appIcon(pkg, 24))
        val hv = vbox()
        hv.addView(text(label, 22f, col(R.attr.nvText), true))
        hv.addView(muted(if (AppInfo.isDemo(pkg)) "App de ejemplo" else if (pkg == AppInfo.SYSTEM) "Consultas que Android no pudo atribuir a una app" else pkg, 12f))
        head.addView(hv, lp(w = 0, weight = 1f, start = 14))
        box.addView(head)

        val rangeTxt = if (days <= 1) "hoy" else "en $days días"
        box.addView(text("Habla con ${comps.size} " + (if (comps.size == 1) "empresa" else "empresas") + " de seguimiento $rangeTxt (${fmt(total)} consultas).", 18f, col(R.attr.nvText), true), lp(top = 16))
        box.addView(muted("$ads de publicidad · $outside con sede fuera del EEE", 13f), lp(top = 4))

        box.addView(h2("Con quién habla"), lp(top = 18))
        for (c in comps) {
            val co = Knowledge.byId(c.id) ?: continue
            box.addView(companyRow(co, co.cat.label + " · " + co.country, fmt(c.n)) { dlg.dismiss(); showCompany(co.id) })
        }

        box.addView(notBox("Muchas apps gratuitas se financian con publicidad y usan analítica. Tener rastreadores no convierte una app en peligrosa, pero conviene saber con quién habla. La atribución a cada app es aproximada."), lp(top = 16))

        if (AppInfo.isReal(pkg)) {
            box.addView(h2("Qué puedes hacer"), lp(top = 18))
            box.addView(button("Abrir ajustes y permisos de la app", true) {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                i.data = Uri.fromParts("package", pkg, null)
                openSettingsIntent(i)
                markChallenge("perms")
            }, lp(top = 8))
        }
        box.addView(button("Restablecer mi ID de publicidad", false) {
            openSettingsIntent(Intent(Settings.ACTION_PRIVACY_SETTINGS))
            markChallenge("ad_id")
        }, lp(top = 8))
    }
}

// ---------- Ajustes ----------

fun MainActivity.showSettings() {
    openSheet { box, dlg ->
        box.addView(text("Ajustes", 24f, col(R.attr.nvText), true))

        box.addView(h2("Cómo prefieres verlo"), lp(top = 18))
        val descr = muted("", 13f)
        val prof = Prefs.profile(this)
        val descTexts = listOf(
            "Explorer: rápido, visual y directo.",
            "Reader: más contexto y texto para profundizar.",
            "Assisted: letra grande, pocas cosas por pantalla y voz."
        )
        descr.text = descTexts[prof]
        box.addView(segmented(listOf("Explorer", "Reader", "Assisted"), prof) {
            Prefs.setProfile(this, it)
            if (it == 2) Prefs.setHc(this, true)
            dlg.dismiss()
            recreate()
        }, lp(top = 8))
        box.addView(descr, lp(top = 6))

        val hc = SwitchCompat(this)
        hc.text = "Alto contraste"
        hc.setTextColor(col(R.attr.nvText))
        hc.textSize = 16f * Prefs.scale(this)
        hc.isChecked = Prefs.hc(this)
        hc.setOnCheckedChangeListener { _, v ->
            Prefs.setHc(this, v)
            dlg.dismiss()
            recreate()
        }
        box.addView(hc, lp(top = 10))

        box.addView(h2("Cuánto tiempo guardo los datos"), lp(top = 22))
        box.addView(muted("Solo en tu móvil. Pasado ese tiempo se borran solos.", 13f), lp(top = 2))
        val rets = listOf(7, 30, 90)
        var ri = rets.indexOf(Prefs.retention(this))
        if (ri < 0) ri = 1
        box.addView(segmented(listOf("7 días", "30 días", "90 días"), ri) { Prefs.setRetention(this, rets[it]) }, lp(top = 8))

        box.addView(h2("Modo escudo (opcional)"), lp(top = 22))
        box.addView(muted("Además de mostrar, puede bloquear la consulta de los rastreadores que elijas. Puede hacer que alguna app falle o muestre anuncios en blanco. Está apagado por defecto.", 13f), lp(top = 2))
        val sh = SwitchCompat(this)
        sh.text = "Bloquear rastreadores"
        sh.setTextColor(col(R.attr.nvText))
        sh.textSize = 16f * Prefs.scale(this)
        sh.isChecked = Prefs.shield(this)
        sh.setOnCheckedChangeListener { _, v ->
            Prefs.setShield(this, v)
            RadarVpnService.loadConfig(this)
        }
        box.addView(sh, lp(top = 8))
        val cats = listOf(Cat.PUB, Cat.ATR, Cat.ANA, Cat.SOC)
        for (k in cats) {
            val sw = SwitchCompat(this)
            sw.text = k.label + " — " + k.plain.lowercase()
            sw.setTextColor(col(R.attr.nvMuted))
            sw.textSize = 14f * Prefs.scale(this)
            sw.isChecked = Prefs.shieldCats(this).contains(k.key)
            sw.setOnCheckedChangeListener { _, v ->
                val cur = HashSet<String>(Prefs.shieldCats(this))
                if (v) cur.add(k.key) else cur.remove(k.key)
                Prefs.setShieldCats(this, cur)
                RadarVpnService.loadConfig(this)
            }
            box.addView(sw, lp(top = 4))
        }
        box.addView(muted("Las notificaciones y los informes de fallos nunca se bloquean.", 12f), lp(top = 6))

        box.addView(h2("Servidor DNS"), lp(top = 22))
        val cur = Prefs.dns(this)
        box.addView(muted("Las consultas se reenvían al servidor DNS de tu red (el que ya usabas). Si quieres otro, escríbelo (IPv4). Se aplica al reiniciar el radar. Actual: " + (if (cur.isEmpty()) "automático" else cur), 13f), lp(top = 2))
        box.addView(button("Cambiar servidor DNS", false) {
            val et = EditText(this)
            et.hint = "Por ejemplo 9.9.9.9 (vacío = automático)"
            et.inputType = InputType.TYPE_CLASS_PHONE
            et.setText(cur)
            AlertDialog.Builder(this)
                .setTitle("Servidor DNS")
                .setView(et)
                .setPositiveButton("Guardar") { _, _ ->
                    Prefs.setDns(this, et.text.toString().trim())
                    toast("Guardado. Reinicia el radar para aplicarlo.")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }, lp(top = 8))

        box.addView(h2("Datos"), lp(top = 22))
        if (Store.get(this).hasDemo()) {
            box.addView(button("Quitar datos de ejemplo", false) { dlg.dismiss(); clearDemo() }, lp(top = 8))
        } else {
            box.addView(button("Ver con datos de ejemplo", false) { dlg.dismiss(); loadDemo() }, lp(top = 8))
        }
        box.addView(button("Exportar mis datos (JSON abierto)", false) { dlg.dismiss(); exportData() }, lp(top = 8))
        box.addView(button("Borrar todos mis datos", false) {
            AlertDialog.Builder(this)
                .setTitle("¿Borrar todo?")
                .setMessage("Se eliminará todo el historial de rastreadores guardado en este móvil. No se puede deshacer.")
                .setPositiveButton("Borrar") { _, _ ->
                    Store.get(this).clearAll()
                    refreshAll()
                    toast("Datos borrados.")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }, lp(top = 8))

        box.addView(h2("Acerca de"), lp(top = 22))
        box.addView(text("Nodo de Verdad enseña quién recibe señales de tu móvil y quién hay detrás de cada empresa. Funciona solo en tu dispositivo: no tiene cuentas, no tiene analítica y no envía tus datos a ningún servidor.\n\nQué ve: únicamente los nombres de servidor de las consultas DNS. Nunca el contenido de lo que envías o recibes. Solo guarda las que coinciden con rastreadores conocidos; del resto solo cuenta cuántas hay.\n\nLímites: no ve las apps que usan DNS cifrado propio, la atribución a cada app es aproximada, la base de empresas es una lista curada v1 que puede tener errores y aparecer en ella no implica ilegalidad.\n\nCorrecciones y derecho de réplica: el canal de contacto debe indicarlo quien publique esta app. Código abierto, pensado para licencia AGPL-3.0.", 13f, col(R.attr.nvMuted)), lp(top = 6))
    }
}

// ---------- Exportación ----------

fun MainActivity.exportData() {
    if (Store.get(this).hasDemo()) {
        toast("Los datos de ejemplo no se exportan: son inventados.")
        return
    }
    if (!Store.get(this).hasAnyData()) {
        toast("Aún no hay datos que exportar. Enciende el radar un rato.")
        return
    }
    AlertDialog.Builder(this)
        .setTitle("Exportar (últimos 30 días)")
        .setItems(arrayOf("Sin nombres de apps (recomendado)", "Con nombres de apps")) { _, which ->
            try {
                val f = Share.exportJson(this, 30, which == 1)
                Share.send(this, f, "application/json", "Exportación abierta de Nodo de Verdad (esquema device-export 1.0).")
            } catch (e: Exception) {
                toast("No se pudo exportar: " + e.message)
            }
        }
        .setNegativeButton("Cancelar", null)
        .show()
}

fun MainActivity.shareRadar() {
    val st = Store.get(this)
    if (st.hasDemo()) {
        toast("Esto es un ejemplo con datos inventados. Enciende el radar para compartir tu dato real.")
        return
    }
    val days = Prefs.range(this)
    val sum = st.summary(days)
    val f = try {
        Share.card(this, days)
    } catch (e: Exception) {
        null
    }
    if (f == null) {
        toast("Aún no hay datos para compartir.")
        return
    }
    Share.send(this, f, "image/png", "Mi móvil habló con ${sum.companies} empresas de seguimiento. Descubre quién rastrea el tuyo con Nodo de Verdad.")
    markChallenge("share")
}

@Suppress("unused")
private fun centered(v: View): View {
    if (v.layoutParams is LinearLayout.LayoutParams) (v.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
    return v
}
