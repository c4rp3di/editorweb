package org.nododeverdad.app

import android.content.Context
import android.graphics.drawable.Drawable

/** Nombre e icono de las apps que aparecen en el radar. */
object AppInfo {
    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, Drawable?>()

    const val SYSTEM = "system"

    fun isDemo(pkg: String): Boolean = pkg.startsWith("demo:")

    fun label(ctx: Context, pkg: String): String {
        if (isDemo(pkg)) return pkg.substring(5)
        if (pkg == SYSTEM) return "Sistema Android y otras apps"
        val cached = labels[pkg]
        if (cached != null) return cached
        var out = pkg
        try {
            val pm = ctx.packageManager
            out = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            // App no visible o desinstalada: se muestra el nombre de paquete.
        }
        labels[pkg] = out
        return out
    }

    fun icon(ctx: Context, pkg: String): Drawable? {
        if (isDemo(pkg) || pkg == SYSTEM) return null
        if (icons.containsKey(pkg)) return icons[pkg]
        var d: Drawable? = null
        try {
            d = ctx.packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            // sin icono
        }
        icons[pkg] = d
        return d
    }

    /** ¿Se puede abrir la ficha de ajustes de esta app? */
    fun isReal(pkg: String): Boolean = !isDemo(pkg) && pkg != SYSTEM
}
