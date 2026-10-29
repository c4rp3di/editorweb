package org.nododeverdad.app

import android.content.Context
import java.util.Calendar
import java.util.Random

/**
 * Datos de ejemplo para probar la app sin encender el radar.
 * Son inventados: se marcan siempre como «ejemplo» y no se pueden exportar ni compartir.
 */
object Demo {

    private val apps: List<Pair<String, List<String>>> = listOf(
        Pair("Juego de puzles", listOf("applovin", "unity", "ironsource", "google", "meta", "vungle", "chartboost", "adjust", "inmobi", "mintegral", "pangle", "tapjoy")),
        Pair("App del tiempo", listOf("google", "criteo", "taboola", "adform", "pubmatic", "magnite", "openx", "xandr", "ttd", "amazon", "comscore")),
        Pair("Vídeos cortos", listOf("tiktok", "google", "appsflyer", "snap", "meta", "branch", "pangle", "sentry")),
        Pair("Linterna", listOf("google", "startapp", "inmobi", "yandex", "umeng", "mintegral", "applovin", "flurry")),
        Pair("Tienda de ropa", listOf("meta", "google", "braze", "onesignal", "adjust", "criteo", "hotjar", "contentsquare", "amplitude", "mixpanel", "pinterest")),
        Pair("Diario digital", listOf("google", "outbrain", "taboola", "teads", "quantcast", "comscore", "adobe", "lotame", "liveramp", "equativ", "indexexchange"))
    )

    fun load(ctx: Context) {
        val st = Store.get(ctx)
        st.clearAll()
        val rnd = Random(42)
        val ordinal = HashMap<String, Int>()
        var idx = 0
        for (a in apps) {
            for (id in a.second) {
                if (!ordinal.containsKey(id)) {
                    ordinal[id] = idx
                    idx++
                }
            }
        }
        val dayHits = IntArray(15)
        for (a in apps) {
            val pkg = "demo:" + a.first
            for (id in a.second) {
                val co = Knowledge.byId(id) ?: continue
                val token = co.domains[0]
                val domain = Knowledge.domainOf(token)
                val cat = Knowledge.catOf(token, co).key
                val k = ordinal[id] ?: 0
                var from = 0
                var to = 13
                if (k % 7 == 3) {
                    from = 0
                    to = 2
                } else if (k % 9 == 4) {
                    from = 9
                    to = 13
                }
                for (ago in from..to) {
                    val cal = Calendar.getInstance()
                    cal.add(Calendar.DAY_OF_YEAR, -ago)
                    cal.set(Calendar.HOUR_OF_DAY, 9 + rnd.nextInt(10))
                    val ts = cal.timeInMillis
                    val day = Store.dayOf(ts)
                    val n = 1 + rnd.nextInt(28)
                    st.insertDemoRow(day, pkg, domain, id, cat, n, 0, ts, ts + 60000L)
                    dayHits[ago] = dayHits[ago] + n
                }
            }
        }
        for (ago in 0..13) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -ago)
            val total = dayHits[ago] * 9 + 300 + rnd.nextInt(400)
            st.insertDemoDaily(Store.dayOf(cal.timeInMillis), total, 0)
        }
    }
}
