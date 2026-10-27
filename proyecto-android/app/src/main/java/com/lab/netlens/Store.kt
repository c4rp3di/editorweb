package com.lab.netlens

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AppStat(val pkg: String, val label: String) {
    val up = AtomicLong(); val down = AtomicLong()
    val flows = AtomicInteger(); val clearFlows = AtomicInteger()
    val mitmCount = AtomicInteger()
    val hosts: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    val trackers: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    val sensitive: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
}

class Flow(val id: Long, val stat: AppStat, val proto: String, val dstIp: String, val dstPort: Int) {
    val start: Long = System.currentTimeMillis()
    @Volatile var last: Long = start
    @Volatile var host: String? = null
    @Volatile var closed: Boolean = false
    @Volatile var mitm: Boolean = false
    val up = AtomicLong(); val down = AtomicLong()
    val notes: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet<String>())
    fun addUp(n: Int) { up.addAndGet(n.toLong()); stat.up.addAndGet(n.toLong()); last = System.currentTimeMillis() }
    fun addDown(n: Int) { down.addAndGet(n.toLong()); stat.down.addAndGet(n.toLong()); last = System.currentTimeMillis() }
    fun noteHost(h: String) {
        if (h.isEmpty()) return
        host = h; stat.hosts.add(h)
        val c = Dpi.category(h)
        if (c != null) { notes.add("🎯 Posible destino de " + c.lowercase() + ": " + h); stat.trackers.add(c + "|" + h) }
    }
    fun name(): String = host ?: Store.dns[dstIp] ?: dstIp
}

object Store {
    private val lock = Any()
    private val list = ArrayList<Flow>()
    private val ids = AtomicLong()
    val apps = ConcurrentHashMap<String, AppStat>()
    val dns = ConcurrentHashMap<String, String>()
    @Volatile var running = false
    @Volatile var blockQuic = false
    @Volatile var mitmEnabled = false

    fun app(pkg: String, label: String): AppStat = apps.getOrPut(pkg) { AppStat(pkg, label) }
    fun newFlow(stat: AppStat, proto: String, ip: String, port: Int): Flow {
        val f = Flow(ids.incrementAndGet(), stat, proto, ip, port)
        stat.flows.incrementAndGet()
        dns[ip]?.let { f.noteHost(it) }
        synchronized(lock) { list.add(f); while (list.size > 1200) list.removeAt(0) }
        return f
    }
    fun snapshot(): List<Flow> = synchronized(lock) { ArrayList(list) }
    fun clear() { synchronized(lock) { list.clear() }; apps.clear() }
}