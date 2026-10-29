package org.nododeverdad.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView

interface Screen {
    val view: View
    fun refresh()
}

class MainActivity : AppCompatActivity() {

    private lateinit var frame: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: BottomNavigationView
    private lateinit var screens: List<Screen>
    private lateinit var radar: ScreenRadar
    private var current = 0
    private val handler = Handler(Looper.getMainLooper())
    private val navIds = intArrayOf(R.id.nav_radar, R.id.nav_graph, R.id.nav_companies, R.id.nav_time, R.id.nav_learn)

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) startRadar() else toast("Sin el permiso de VPN el radar no puede encenderse.")
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        requestVpn()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (current == 0) radar.refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (Prefs.hc(this)) R.style.Theme_NV_HC else R.style.Theme_NV)
        super.onCreate(savedInstanceState)
        RadarVpnService.loadConfig(this)

        frame = FrameLayout(this)
        frame.setBackgroundColor(col(R.attr.nvBg))
        val main = vbox()
        content = FrameLayout(this)
        main.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        nav = BottomNavigationView(this)
        nav.inflateMenu(R.menu.bottom_nav)
        nav.labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val colors = intArrayOf(col(R.attr.nvAccent), col(R.attr.nvMuted))
        val csl = ColorStateList(states, colors)
        nav.itemIconTintList = csl
        nav.itemTextColor = csl
        nav.setBackgroundColor(col(R.attr.nvSurface))
        main.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        frame.addView(main, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(frame)

        radar = ScreenRadar(this)
        screens = listOf(radar, ScreenGraph(this), ScreenWho(this), ScreenTime(this), ScreenLearn(this))
        for (s in screens) {
            s.view.visibility = View.GONE
            content.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        nav.setOnItemSelectedListener { item ->
            val idx = navIds.indexOf(item.itemId)
            if (idx >= 0) show(idx)
            true
        }
        nav.selectedItemId = R.id.nav_radar
        show(0)

        if (!Prefs.onboarded(this)) showOnboarding()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
        refreshAll()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun show(i: Int) {
        current = i
        for (k in screens.indices) screens[k].view.visibility = if (k == i) View.VISIBLE else View.GONE
        screens[i].refresh()
    }

    fun goTo(i: Int) {
        nav.selectedItemId = navIds[i]
    }

    fun refreshAll() {
        radar.forceRebuild()
        if (current != 0) screens[current].refresh()
    }

    fun openApp(pkg: String) {
        showApp(pkg)
    }

    fun markChallenge(id: String) {
        val s = HashSet<String>(Prefs.challengesDone(this))
        if (s.add(id)) Prefs.setChallengesDone(this, s)
    }

    // ---------- Encender y apagar ----------

    fun toggleRadar() {
        if (RadarVpnService.running) {
            RadarVpnService.stop(this)
            handler.postDelayed({ refreshAll() }, 600)
            return
        }
        if (!Prefs.vpnExplained(this)) {
            AlertDialog.Builder(this)
                .setTitle("Antes de encender el radar")
                .setMessage(
                    "Android te va a pedir permiso para crear una «VPN». Es normal: el radar usa una VPN local solo para ver las consultas DNS, es decir, los nombres de servidor a los que llaman tus apps.\n\n" +
                        "• No desvía tu tráfico ni lo descifra.\n• No envía nada a ningún servidor nuestro.\n• Reenvía las consultas a tu DNS de siempre.\n\nPuedes apagarlo cuando quieras."
                )
                .setPositiveButton("Entendido") { _, _ ->
                    Prefs.setVpnExplained(this)
                    beginStart()
                }
                .setNegativeButton("Ahora no", null)
                .show()
        } else {
            beginStart()
        }
    }

    private fun beginStart() {
        val st = Store.get(this)
        if (st.hasDemo()) st.clearDemo()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpn()
        }
    }

    private fun requestVpn() {
        val i = VpnService.prepare(this)
        if (i != null) vpnLauncher.launch(i) else startRadar()
    }

    private fun startRadar() {
        RadarVpnService.start(this)
        handler.postDelayed({ refreshAll() }, 800)
    }

    // ---------- Datos de ejemplo ----------

    fun loadDemo() {
        if (RadarVpnService.running) {
            toast("Apaga el radar para ver el ejemplo.")
            return
        }
        val st = Store.get(this)
        if (st.hasAnyData() && !st.hasDemo()) {
            AlertDialog.Builder(this)
                .setTitle("¿Sustituir tus datos?")
                .setMessage("Para mostrar el ejemplo hay que borrar los datos reales guardados en este móvil.")
                .setPositiveButton("Sí, borrar y ver ejemplo") { _, _ ->
                    Demo.load(this)
                    toast("Datos de ejemplo cargados. Son inventados.")
                    refreshAll()
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        Demo.load(this)
        toast("Datos de ejemplo cargados. Son inventados.")
        refreshAll()
    }

    fun clearDemo() {
        Store.get(this).clearDemo()
        refreshAll()
    }

    // ---------- Bienvenida ----------

    private fun showOnboarding() {
        val ov = FrameLayout(this)
        ov.setBackgroundColor(col(R.attr.nvBg))
        ov.isClickable = true
        frame.addView(ov, FrameLayout.LayoutParams(MATCH, MATCH))
        renderStep(ov, 0)
    }

    private fun renderStep(ov: FrameLayout, step: Int) {
        ov.removeAllViews()
        val b = vbox(24)
        b.gravity = Gravity.CENTER_HORIZONTAL

        val rv = RadarView(this)
        rv.active = true
        b.addView(rv, lp(h = dp(200), top = 24))

        val titles = listOf(
            "Tu móvil habla con empresas que no conoces",
            "Un radar que vive solo en tu móvil",
            "Elige cómo quieres verlo"
        )
        val bodies = listOf(
            "Cada vez que abres una app, puede avisar a decenas de empresas de publicidad y analítica. No lo ves, pero pasa.",
            "Esta app te enseña quién es quién. Solo mira a quién llama tu móvil, nunca qué dice. Sin cuentas, sin servidores nuestros, sin anuncios. Nada sale de aquí.",
            "Puedes cambiarlo cuando quieras en Ajustes."
        )
        val t = text(titles[step], 26f, col(R.attr.nvText), true)
        t.gravity = Gravity.CENTER
        b.addView(t, lp(top = 24))
        val d = text(bodies[step], 16f, col(R.attr.nvMuted))
        d.gravity = Gravity.CENTER
        b.addView(d, lp(top = 12))

        if (step < 2) {
            b.addView(button(if (step == 0) "Siguiente" else "Continuar", true) { renderStep(ov, step + 1) }, lp(top = 28))
            b.addView(button("Saltar", false) { finishOnboarding(ov, 0) }, lp(top = 8))
        } else {
            b.addView(button("Explorer · rápido y visual", true) { finishOnboarding(ov, 0) }, lp(top = 20))
            b.addView(button("Reader · más contexto y texto", false) { finishOnboarding(ov, 1) }, lp(top = 8))
            b.addView(button("Assisted · letra grande y voz", false) { finishOnboarding(ov, 2) }, lp(top = 8))
        }
        val sv = android.widget.ScrollView(this)
        sv.addView(b)
        ov.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun finishOnboarding(ov: FrameLayout, profile: Int) {
        Prefs.setOnboarded(this)
        val changed = profile != Prefs.profile(this)
        Prefs.setProfile(this, profile)
        if (profile == 2) Prefs.setHc(this, true)
        if (changed || profile == 2) {
            recreate()
        } else {
            frame.removeView(ov)
        }
    }
}
