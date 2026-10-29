package org.nododeverdad.app

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Summary(val queries: Int, val blocked: Int, val hits: Int, val companies: Int, val apps: Int)
class CompanyStat(val id: String, val n: Int, val blocked: Int, val apps: Int, val first: Long, val last: Long)
class AppStat(val pkg: String, val n: Int, val companies: Int)
class EdgeStat(val pkg: String, val company: String, val n: Int)
class DomainStat(val domain: String, val cat: String, val n: Int, val first: Long, val last: Long)

/**
 * Almacén local (SQLite). Principios de privacidad:
 *  - Solo se guardan consultas que coinciden con rastreadores conocidos.
 *  - El resto de consultas solo suma a un contador diario, sin guardar el dominio.
 *  - Todo queda en el móvil y se purga según la retención elegida.
 */
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "nv.db", null, 1) {

    companion object {
        @Volatile
        private var inst: Store? = null

        fun get(c: Context): Store {
            val i = inst
            if (i != null) return i
            synchronized(this) {
                val j = inst
                if (j != null) return j
                val n = Store(c.applicationContext)
                inst = n
                return n
            }
        }

        fun dayOf(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

        fun dayMinus(days: Int): String {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, -days)
            return dayOf(c.timeInMillis)
        }
    }

    private class P(var n: Int, var blocked: Int, var first: Long, var last: Long, val company: String, val cat: String)

    private val pending = HashMap<String, P>()
    private val pendTotal = HashMap<String, IntArray>()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE agg (" +
                "day TEXT NOT NULL, pkg TEXT NOT NULL, domain TEXT NOT NULL, company TEXT NOT NULL, cat TEXT NOT NULL, " +
                "n INTEGER NOT NULL DEFAULT 0, blocked INTEGER NOT NULL DEFAULT 0, " +
                "first_ts INTEGER NOT NULL, last_ts INTEGER NOT NULL, demo INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY (day, pkg, domain))"
        )
        db.execSQL("CREATE INDEX idx_agg_company ON agg(company)")
        db.execSQL(
            "CREATE TABLE daily (day TEXT PRIMARY KEY, total INTEGER NOT NULL DEFAULT 0, " +
                "blocked INTEGER NOT NULL DEFAULT 0, demo INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versión 1: nada que migrar todavía.
    }

    // ---------- Escritura desde el servicio (en memoria + volcado periódico) ----------

    /** Cuenta una consulta DNS cualquiera, sin guardar de qué dominio era. */
    fun tick(blocked: Boolean) {
        val day = dayOf(System.currentTimeMillis())
        synchronized(pending) {
            val a = pendTotal.getOrPut(day) { IntArray(2) }
            a[0] = a[0] + 1
            if (blocked) a[1] = a[1] + 1
        }
    }

    /** Registra una consulta que coincide con un rastreador conocido. */
    fun record(pkg: String, domain: String, company: String, cat: String, blocked: Boolean) {
        val now = System.currentTimeMillis()
        val key = dayOf(now) + "|" + pkg + "|" + domain
        synchronized(pending) {
            val p = pending[key]
            if (p == null) {
                pending[key] = P(1, if (blocked) 1 else 0, now, now, company, cat)
            } else {
                p.n = p.n + 1
                if (blocked) p.blocked = p.blocked + 1
                p.last = now
            }
        }
    }

    fun flush() {
        val snapP = HashMap<String, P>()
        val snapT = HashMap<String, IntArray>()
        synchronized(pending) {
            if (pending.isEmpty() && pendTotal.isEmpty()) return
            snapP.putAll(pending)
            snapT.putAll(pendTotal)
            pending.clear()
            pendTotal.clear()
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((k, p) in snapP) {
                val parts = k.split("|", limit = 3)
                db.execSQL(
                    "INSERT OR IGNORE INTO agg(day,pkg,domain,company,cat,n,blocked,first_ts,last_ts,demo) VALUES(?,?,?,?,?,0,0,?,?,0)",
                    arrayOf<Any>(parts[0], parts[1], parts[2], p.company, p.cat, p.first, p.last)
                )
                db.execSQL(
                    "UPDATE agg SET n = n + ?, blocked = blocked + ?, last_ts = MAX(last_ts, ?) WHERE day = ? AND pkg = ? AND domain = ?",
                    arrayOf<Any>(p.n, p.blocked, p.last, parts[0], parts[1], parts[2])
                )
            }
            for ((day, a) in snapT) {
                db.execSQL("INSERT OR IGNORE INTO daily(day,total,blocked,demo) VALUES(?,0,0,0)", arrayOf<Any>(day))
                db.execSQL(
                    "UPDATE daily SET total = total + ?, blocked = blocked + ? WHERE day = ?",
                    arrayOf<Any>(a[0], a[1], day)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- Datos de ejemplo ----------

    fun insertDemoRow(day: String, pkg: String, domain: String, company: String, cat: String, n: Int, blocked: Int, first: Long, last: Long) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO agg(day,pkg,domain,company,cat,n,blocked,first_ts,last_ts,demo) VALUES(?,?,?,?,?,?,?,?,?,1)",
            arrayOf<Any>(day, pkg, domain, company, cat, n, blocked, first, last)
        )
    }

    fun insertDemoDaily(day: String, total: Int, blocked: Int) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO daily(day,total,blocked,demo) VALUES(?,?,?,1)",
            arrayOf<Any>(day, total, blocked)
        )
    }

    fun hasDemo(): Boolean = scalarInt("SELECT COUNT(*) FROM agg WHERE demo = 1", emptyArray()) > 0

    fun hasAnyData(): Boolean = scalarInt("SELECT COUNT(*) FROM agg", emptyArray()) > 0

    fun clearDemo() {
        val db = writableDatabase
        db.execSQL("DELETE FROM agg WHERE demo = 1")
        db.execSQL("DELETE FROM daily WHERE demo = 1")
    }

    fun clearAll() {
        synchronized(pending) {
            pending.clear()
            pendTotal.clear()
        }
        val db = writableDatabase
        db.execSQL("DELETE FROM agg")
        db.execSQL("DELETE FROM daily")
    }

    fun purge(retentionDays: Int) {
        val cutoff = dayMinus(retentionDays)
        val db = writableDatabase
        db.execSQL("DELETE FROM agg WHERE day < ?", arrayOf<Any>(cutoff))
        db.execSQL("DELETE FROM daily WHERE day < ?", arrayOf<Any>(cutoff))
    }

    // ---------- Lectura ----------

    private fun scalarInt(sql: String, args: Array<String>): Int {
        val c: Cursor = readableDatabase.rawQuery(sql, args)
        try {
            return if (c.moveToFirst()) c.getInt(0) else 0
        } finally {
            c.close()
        }
    }

    private fun since(days: Int): String = dayMinus(Math.max(0, days - 1))

    fun summary(days: Int): Summary {
        val s = since(days)
        val args = arrayOf(s)
        val queries = scalarInt("SELECT COALESCE(SUM(total),0) FROM daily WHERE day >= ?", args)
        val blocked = scalarInt("SELECT COALESCE(SUM(blocked),0) FROM daily WHERE day >= ?", args)
        val hits = scalarInt("SELECT COALESCE(SUM(n),0) FROM agg WHERE day >= ?", args)
        val comps = scalarInt("SELECT COUNT(DISTINCT company) FROM agg WHERE day >= ?", args)
        val apps = scalarInt("SELECT COUNT(DISTINCT pkg) FROM agg WHERE day >= ?", args)
        return Summary(queries, blocked, hits, comps, apps)
    }

    fun byCompany(days: Int): List<CompanyStat> {
        val out = ArrayList<CompanyStat>()
        val c = readableDatabase.rawQuery(
            "SELECT company, SUM(n), SUM(blocked), COUNT(DISTINCT pkg), MIN(first_ts), MAX(last_ts) FROM agg " +
                "WHERE day >= ? GROUP BY company ORDER BY SUM(n) DESC",
            arrayOf(since(days))
        )
        try {
            while (c.moveToNext()) {
                out.add(CompanyStat(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getLong(5)))
            }
        } finally {
            c.close()
        }
        return out
    }

    /** Historial completo (todo lo que hay guardado) para altas y bajas. */
    fun lifetimeByCompany(): List<CompanyStat> {
        val out = ArrayList<CompanyStat>()
        val c = readableDatabase.rawQuery(
            "SELECT company, SUM(n), SUM(blocked), COUNT(DISTINCT pkg), MIN(first_ts), MAX(last_ts) FROM agg " +
                "GROUP BY company ORDER BY MIN(first_ts) DESC",
            emptyArray()
        )
        try {
            while (c.moveToNext()) {
                out.add(CompanyStat(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getLong(5)))
            }
        } finally {
            c.close()
        }
        return out
    }

    fun firstTs(): Long {
        val c = readableDatabase.rawQuery("SELECT MIN(first_ts) FROM agg", emptyArray())
        try {
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } finally {
            c.close()
        }
    }

    fun byApp(days: Int): List<AppStat> {
        val out = ArrayList<AppStat>()
        val c = readableDatabase.rawQuery(
            "SELECT pkg, SUM(n), COUNT(DISTINCT company) FROM agg WHERE day >= ? " +
                "GROUP BY pkg ORDER BY COUNT(DISTINCT company) DESC, SUM(n) DESC",
            arrayOf(since(days))
        )
        try {
            while (c.moveToNext()) out.add(AppStat(c.getString(0), c.getInt(1), c.getInt(2)))
        } finally {
            c.close()
        }
        return out
    }

    fun edges(days: Int): List<EdgeStat> {
        val out = ArrayList<EdgeStat>()
        val c = readableDatabase.rawQuery(
            "SELECT pkg, company, SUM(n) FROM agg WHERE day >= ? GROUP BY pkg, company",
            arrayOf(since(days))
        )
        try {
            while (c.moveToNext()) out.add(EdgeStat(c.getString(0), c.getString(1), c.getInt(2)))
        } finally {
            c.close()
        }
        return out
    }

    /** Número de empresas distintas por categoría. */
    fun catCounts(days: Int): Map<String, Int> {
        val out = HashMap<String, Int>()
        val c = readableDatabase.rawQuery(
            "SELECT cat, COUNT(DISTINCT company) FROM agg WHERE day >= ? GROUP BY cat",
            arrayOf(since(days))
        )
        try {
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
        } finally {
            c.close()
        }
        return out
    }

    fun appsOfCompany(company: String, days: Int): List<AppStat> {
        val out = ArrayList<AppStat>()
        val c = readableDatabase.rawQuery(
            "SELECT pkg, SUM(n), 1 FROM agg WHERE company = ? AND day >= ? GROUP BY pkg ORDER BY SUM(n) DESC",
            arrayOf(company, since(days))
        )
        try {
            while (c.moveToNext()) out.add(AppStat(c.getString(0), c.getInt(1), c.getInt(2)))
        } finally {
            c.close()
        }
        return out
    }

    fun companiesOfApp(pkg: String, days: Int): List<CompanyStat> {
        val out = ArrayList<CompanyStat>()
        val c = readableDatabase.rawQuery(
            "SELECT company, SUM(n), SUM(blocked), 1, MIN(first_ts), MAX(last_ts) FROM agg " +
                "WHERE pkg = ? AND day >= ? GROUP BY company ORDER BY SUM(n) DESC",
            arrayOf(pkg, since(days))
        )
        try {
            while (c.moveToNext()) {
                out.add(CompanyStat(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getLong(5)))
            }
        } finally {
            c.close()
        }
        return out
    }

    fun domainsOfCompany(company: String, days: Int): List<DomainStat> {
        val out = ArrayList<DomainStat>()
        val c = readableDatabase.rawQuery(
            "SELECT domain, cat, SUM(n), MIN(first_ts), MAX(last_ts) FROM agg WHERE company = ? AND day >= ? " +
                "GROUP BY domain, cat ORDER BY SUM(n) DESC",
            arrayOf(company, since(days))
        )
        try {
            while (c.moveToNext()) {
                out.add(DomainStat(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3), c.getLong(4)))
            }
        } finally {
            c.close()
        }
        return out
    }

    /** Filas por app y dominio para la exportación. */
    fun exportRows(days: Int): List<Array<String>> {
        val out = ArrayList<Array<String>>()
        val c = readableDatabase.rawQuery(
            "SELECT pkg, domain, company, cat, SUM(n), MIN(first_ts), MAX(last_ts) FROM agg WHERE day >= ? AND demo = 0 " +
                "GROUP BY pkg, domain, company, cat ORDER BY pkg",
            arrayOf(since(days))
        )
        try {
            while (c.moveToNext()) {
                out.add(arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4).toString(), c.getLong(5).toString(), c.getLong(6).toString()))
            }
        } finally {
            c.close()
        }
        return out
    }
}
