package com.lab.netlens

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object Blocker {
    val customBlocks = CopyOnWriteArraySet<String>()
    val customAllows = CopyOnWriteArraySet<String>()
    val perAppBlocks = ConcurrentHashMap<String, CopyOnWriteArraySet<String>>()

    private val defaultBlocked = setOf(
        "doubleclick.net","googlesyndication.com","googleadservices.com","admob.com",
        "applovin.com","unityads.unity3d.com","vungle.com","adcolony.com","inmobi.com",
        "mopub.com","moloco.com","pubmatic.com","criteo.com","taboola.com","outbrain.com",
        "adnxs.com","rubiconproject.com","chartboost.com","smaato.net",
        "google-analytics.com","googletagmanager.com","app-measurement.com","appsflyer.com",
        "adjust.com","adjust.io","branch.io","kochava.com","mixpanel.com","amplitude.com",
        "segment.io","segment.com","flurry.com","singular.net","braze.com","onesignal.com",
        "clevertap.com","graph.facebook.com","facebook.net"
    )

    fun baseDomain(host: String): String {
        if (host.isEmpty() || host.all { it.isDigit() || it == '.' }) return host
        val p = host.split(".")
        return if (p.size <= 2) host else p[p.size - 2] + "." + p[p.size - 1]
    }

    fun isBlocked(pkg: String, host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        val base = baseDomain(h)
        if (customAllows.contains(h) || customAllows.contains(base)) return false
        if (customBlocks.contains(h) || customBlocks.contains(base)) return true
        perAppBlocks[pkg]?.let { if (it.contains(h) || it.contains(base)) return true }
        return defaultBlocked.any { h == it || h.endsWith("." + it) }
    }

    fun block(pkg: String, host: String) {
        perAppBlocks.getOrPut(pkg) { CopyOnWriteArraySet() }.add(host)
    }

    fun unblockAll(pkg: String) { perAppBlocks.remove(pkg) }
}
