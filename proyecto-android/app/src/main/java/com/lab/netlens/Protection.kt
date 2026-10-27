package com.lab.netlens

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object Protection {
    private lateinit var prefs: SharedPreferences
    @Volatile var rules: Set<String> = emptySet()
        private set
    val blocked = AtomicLong()
    val byApp = ConcurrentHashMap<String, AtomicLong>()
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("protection", Context.MODE_PRIVATE)
        rules = prefs.getStringSet("rules", emptySet())?.toSet() ?: emptySet()
    }
    fun save(text: String) {
        val next = text.lines().map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        require(next.all { Regex("[+-]([^| ]+\\|)?[a-z0-9.-]+").matches(it) }) {
            "Formato: -dominio.com, +dominio.com o -paquete|dominio.com"
        }
        prefs.edit().putStringSet("rules", next).apply(); rules = next
    }
    fun matches(host: String, rule: String): Boolean = host == rule || host.endsWith("." + rule)
    fun reason(pkg: String, host: String?): String? {
        if (!enabled || host.isNullOrBlank()) return null
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        val applicable = rules.filter { val r=it.drop(1); !r.contains('|') || r.substringBefore('|') == pkg }
        fun domain(r: String) = r.drop(1).substringAfter('|')
        if (applicable.any { it.startsWith("+") && matches(h, domain(it)) }) return null
        if (applicable.any { it.startsWith("-") && matches(h, domain(it)) }) return "Regla personalizada: $h"
        val category = Dpi.category(h)
        return if (category != null && !category.contains("fallos", true)) "Lista integrada: $category / $h" else null
    }
    fun record(pkg: String) { blocked.incrementAndGet(); byApp.getOrPut(pkg) { AtomicLong() }.incrementAndGet() }
    fun stats(): String = "Decisiones de bloqueo en este proceso: ${blocked.get()}\n" +
        byApp.entries.sortedByDescending { it.value.get() }.joinToString("\n") { "${it.key}: ${it.value.get()}" } +
        "\nNo son bytes ahorrados ni fugas confirmadas. Reiniciar el proceso reinicia contadores."
}
