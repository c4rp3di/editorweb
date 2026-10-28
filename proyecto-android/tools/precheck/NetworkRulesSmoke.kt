package com.lab.netlens

import android.content.Context
import android.content.SharedPreferences
import java.io.File

private class MemoryPreferences : SharedPreferences {
    private val strings = mutableMapOf<String, String?>()
    private val bools = mutableMapOf<String, Boolean>()
    private val longs = mutableMapOf<String, Long>()
    private val sets = mutableMapOf<String, Set<String>?>()
    override fun getString(key: String, defValue: String?): String? = strings[key] ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = bools[key] ?: defValue
    override fun getLong(key: String, defValue: Long): Long = longs[key] ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = sets[key] ?: defValues
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor { strings[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { bools[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { longs[key] = value; return this }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor { sets[key] = values; return this }
        override fun apply() {}
    }
}
private class MemoryContext : Context() {
    private val prefs = MemoryPreferences()
    override val filesDir: File = File(System.getProperty("java.io.tmpdir"), "netlens-precheck-context").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
}

fun main() {
    NetworkTools.init(MemoryContext())
    NetworkTools.enabled = true
    NetworkTools.list = emptySet()
    NetworkTools.saveRules("-example.com")
    check(NetworkTools.deny("app.a", "example.com"))
    check(NetworkTools.deny("app.a", "api.example.com"))
    check(!NetworkTools.deny("app.a", "notexample.com"))
    NetworkTools.saveRules("-example.com\n+safe.example.com")
    check(NetworkTools.deny("app.a", "api.example.com"))
    check(!NetworkTools.deny("app.a", "safe.example.com"))
    NetworkTools.saveRules("-app.a|example.com")
    check(NetworkTools.deny("app.a", "example.com"))
    check(!NetworkTools.deny("app.b", "example.com"))
    NetworkTools.saveRules("+example.com\n-app.a|example.com")
    check(NetworkTools.deny("app.a", "example.com")) { "Regla específica de paquete debe ganar en igualdad de dominio" }
    check(!NetworkTools.deny("app.b", "example.com")) { "Regla global de permiso debe seguir aplicando a otros paquetes" }
    println("PASS — suffix boundaries, domain specificity, package-over-global precedence, and app scoping")
}
