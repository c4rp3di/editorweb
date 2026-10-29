package org.nododeverdad.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

fun fmt(n: Int): String = String.format(Locale("es", "ES"), "%,d", n)

fun Context.catColor(cat: Cat): Int = col(cat.attr)

fun Context.initialCircle(letter: String, color: Int, sizeDp: Int): TextView {
    val t = text(letter, 15f, color, true)
    t.gravity = Gravity.CENTER
    t.background = shape(withAlpha(color, 40), sizeDp, withAlpha(color, 130), 1)
    t.layoutParams = LinearLayout.LayoutParams(dp(sizeDp * 2), dp(sizeDp * 2))
    return t
}

fun Context.appIcon(pkg: String, sizeDp: Int = 20): View {
    val d = AppInfo.icon(this, pkg)
    if (d != null) {
        val iv = ImageView(this)
        iv.setImageDrawable(d)
        iv.layoutParams = LinearLayout.LayoutParams(dp(sizeDp * 2), dp(sizeDp * 2))
        iv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return iv
    }
    val label = AppInfo.label(this, pkg)
    val letter = if (label.isNotEmpty()) label.substring(0, 1).uppercase() else "?"
    return initialCircle(letter, col(R.attr.nvAccent), sizeDp)
}

private fun Context.row(left: View, title: String, sub: String, badge: String?, onClick: () -> Unit): View {
    val r = hbox()
    r.minimumHeight = dp(60)
    r.setPadding(dp(4), dp(8), dp(4), dp(8))
    r.foreground = ripple(12)
    r.isClickable = true
    r.isFocusable = true
    r.setOnClickListener { onClick() }
    r.addView(left)
    val mid = vbox()
    mid.addView(text(title, 15f, col(R.attr.nvText), true))
    if (sub.isNotEmpty()) mid.addView(muted(sub, 12f))
    r.addView(mid, lp(w = 0, weight = 1f, start = 12, end = 8))
    if (badge != null) r.addView(text(badge, 13f, col(R.attr.nvMuted), true))
    r.contentDescription = title + ". " + sub + (if (badge != null) ". $badge" else "")
    return r
}

fun Context.companyRow(co: Company, sub: String, badge: String?, onClick: () -> Unit): View {
    val letter = co.name.substring(0, 1).uppercase()
    return row(initialCircle(letter, catColor(co.cat), 18), co.name, sub, badge, onClick)
}

fun Context.appRow(pkg: String, sub: String, badge: String?, onClick: () -> Unit): View {
    return row(appIcon(pkg, 18), AppInfo.label(this, pkg), sub, badge, onClick)
}

fun Context.iconButton(res: Int, desc: String, onClick: () -> Unit): ImageView {
    val b = ImageView(this)
    b.setImageResource(res)
    b.setColorFilter(col(R.attr.nvText))
    b.setPadding(dp(12), dp(12), dp(12), dp(12))
    b.contentDescription = desc
    b.foreground = ripple(24)
    b.isClickable = true
    b.isFocusable = true
    b.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    b.setOnClickListener { onClick() }
    return b
}

/** Caja de «qué NO significa»: la regla de oro del proyecto. */
fun Context.notBox(textStr: String): LinearLayout {
    val b = vbox(14)
    val c = col(R.attr.nvAtr)
    b.background = shape(withAlpha(c, 30), 16, withAlpha(c, 120), 1)
    b.addView(text("Qué NO significa", 13f, c, true))
    b.addView(text(textStr, 14f), lp(top = 4))
    return b
}

fun Context.stat(value: String, label: String, color: Int): LinearLayout {
    val c = card(12)
    c.gravity = Gravity.CENTER_HORIZONTAL
    val v = text(value, 22f, color, true)
    v.gravity = Gravity.CENTER
    c.addView(v)
    val l = muted(label, 11f)
    l.gravity = Gravity.CENTER
    c.addView(l)
    return c
}

