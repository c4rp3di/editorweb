package org.nododeverdad.app

import android.content.Context
import android.content.SharedPreferences

/** Preferencias locales. Nada de esto sale del móvil. */
object Prefs {
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("nv", Context.MODE_PRIVATE)

    // 0 = Explorer, 1 = Reader, 2 = Assisted
    fun profile(c: Context): Int = sp(c).getInt("profile", 0)
    fun setProfile(c: Context, v: Int) { sp(c).edit().putInt("profile", v).apply() }

    fun hc(c: Context): Boolean = sp(c).getBoolean("hc", false)
    fun setHc(c: Context, v: Boolean) { sp(c).edit().putBoolean("hc", v).apply() }

    fun scale(c: Context): Float = if (profile(c) == 2) 1.25f else 1f

    fun onboarded(c: Context): Boolean = sp(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context) { sp(c).edit().putBoolean("onboarded", true).apply() }

    fun retention(c: Context): Int = sp(c).getInt("retention", 30)
    fun setRetention(c: Context, v: Int) { sp(c).edit().putInt("retention", v).apply() }

    fun range(c: Context): Int = sp(c).getInt("range", 7)
    fun setRange(c: Context, v: Int) { sp(c).edit().putInt("range", v).apply() }

    fun dns(c: Context): String = sp(c).getString("dns", "") ?: ""
    fun setDns(c: Context, v: String) { sp(c).edit().putString("dns", v).apply() }

    fun shield(c: Context): Boolean = sp(c).getBoolean("shield", false)
    fun setShield(c: Context, v: Boolean) { sp(c).edit().putBoolean("shield", v).apply() }

    fun shieldCats(c: Context): Set<String> {
        val s = sp(c).getString("shield_cats", "PUB") ?: "PUB"
        val out = HashSet<String>()
        for (p in s.split(",")) if (p.isNotBlank()) out.add(p.trim())
        return out
    }

    fun setShieldCats(c: Context, cats: Set<String>) {
        sp(c).edit().putString("shield_cats", cats.joinToString(",")).apply()
    }

    fun challengesDone(c: Context): Set<String> {
        val s = sp(c).getString("challenges", "") ?: ""
        val out = HashSet<String>()
        for (p in s.split(",")) if (p.isNotBlank()) out.add(p.trim())
        return out
    }

    fun setChallengesDone(c: Context, ids: Set<String>) {
        sp(c).edit().putString("challenges", ids.joinToString(",")).apply()
    }

    fun vpnExplained(c: Context): Boolean = sp(c).getBoolean("vpn_explained", false)
    fun setVpnExplained(c: Context) { sp(c).edit().putBoolean("vpn_explained", true).apply() }

    fun quizBest(c: Context): Int = sp(c).getInt("quiz_best", 0)
    fun setQuizBest(c: Context, v: Int) { sp(c).edit().putInt("quiz_best", v).apply() }
}
