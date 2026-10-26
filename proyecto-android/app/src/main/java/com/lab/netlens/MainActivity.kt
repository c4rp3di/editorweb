package com.lab.netlens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class Row(val title: String, val sub: String, val color: Int, val tag: Any? = null)

class RowAdapter(private val ctx: Context) : BaseAdapter() {
    var rows: List<Row> = emptyList()

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val lay: LinearLayout
        if (convertView is LinearLayout) {
            lay = convertView
        } else {
            lay = LinearLayout(ctx)
            lay.orientation = LinearLayout.VERTICAL
            val pad = (12 * ctx.resources.displayMetrics.density).toInt()
            lay.setPadding(pad, pad - 2, pad, pad - 2)
            val t = TextView(ctx)
            t.textSize = 14f
            t.setTypeface(null, Typeface.BOLD)
            val s = TextView(ctx)
            s.textSize = 12f
            s.setTextColor(Color.parseColor("#8B949E"))
            lay.addView(t)
            lay.addView(s)
        }
        val r = rows[position]
        val t = lay.getChildAt(0) as TextView
        val s = lay.getChildAt(1) as TextView
        t.text = r.title
        t.setTextColor(r.color)
        s.text = r.sub
        s.visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
        return lay
    }
}

class MainActivity : AppCompatActivity() {
    private val cText = Color.parseColor("#E6EDF3")
    private val cBg = Color.parseColor("#0D1117")
    private val cMuted = Color.parseColor("#8B949E")
    private val cOk = Color.parseColor("#3FB950")
    private val cWarn = Color.parseColor("#D29922")
    private val cBad = Color.parseColor("#F85149")
    private val cAccent = Color.parseColor("#58A6FF")

    private lateinit var list: ListView
    private lateinit var adapter: RowAdapter
    private lateinit var btnStart: Button
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private val tabButtons = ArrayList<Button>()
    private var tab = 0
    private var hideDns = true
    private var paused = false
    private var userApps: List<Pair<String, String>> = emptyList()
    @Volatile private var permRows: List<Perms.Info> = emptyList()
    @Volatile private var permLoading = false
    private val handler = Handler(Looper.getMainLooper())

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) launchService()
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        buildUi()
        loadUserApps()
        selectTab(0)
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.fitsSystemWindows = true
        root.setPadding(dp(10), dp(8), dp(10), 0)

        val title = TextView(this)
        title.text = "NetLens · observatorio de red"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(cText)
        val sub = TextView(this)
        sub.text = "Laboratorio didáctico: qué envían tus apps y a dónde. Todo en local."
        sub.textSize = 12f
        sub.setTextColor(cMuted)
        root.addView(title)
        root.addView(sub)

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = android.view.Gravity.CENTER_VERTICAL
        btnStart = Button(this)
        btnStart.setOnClickListener { toggle() }
        spinner = Spinner(this)
        row1.addView(btnStart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        root.addView(row1)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        val cbQuic = CheckBox(this)
        cbQuic.text = "Bloquear QUIC"
        cbQuic.setTextColor(cText)
        cbQuic.textSize = 12f
        cbQuic.setOnCheckedChangeListener { _, on -> Store.blockQuic = on }
        val cbDns = CheckBox(this)
        cbDns.text = "Ocultar DNS"
        cbDns.isChecked = true
        cbDns.setTextColor(cText)
        cbDns.textSize = 12f
        cbDns.setOnCheckedChangeListener { _, on -> hideDns = on; refresh() }
        val cbPause = CheckBox(this)
        cbPause.text = "Pausar"
        cbPause.setTextColor(cText)
        cbPause.textSize = 12f
        cbPause.setOnCheckedChangeListener { _, on -> paused = on }
        row2.addView(cbQuic)
        row2.addView(cbDns)
        row2.addView(cbPause)
        root.addView(row2)

        status = TextView(this)
        status.textSize = 12f
        status.setTextColor(cMuted)
        root.addView(status)

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        val names = listOf("En vivo", "Apps", "Permisos", "Aprende")
        for (i in names.indices) {
            val b = Button(this)
            b.text = names[i]
            b.isAllCaps = false
            b.textSize = 12f
            b.setOnClickListener { selectTab(i) }
            tabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tabButtons.add(b)
        }
        root.addView(tabs)

        list = ListView(this)
        list.setBackgroundColor(cBg)
        list.divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#21262D"))
        list.dividerHeight = 1
        adapter = RowAdapter(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> onRow(adapter.rows[pos]) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun loadUserApps() {
        Thread {
            val res = ArrayList<Pair<String, String>>()
            try {
                for (ai in packageManager.getInstalledApplications(0)) {
                    val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    if (sys || ai.packageName == packageName) continue
                    res.add(Pair(ai.packageName, ai.loadLabel(packageManager).toString()))
                }
            } catch (e: Exception) {
            }
            res.sortBy { it.second.lowercase() }
            runOnUiThread {
                userApps = res
                val labels = ArrayList<String>()
                labels.add("Todas las apps")
                for (a in res) labels.add(a.second)
                val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
                ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = ad
            }
        }.start()
    }

    private fun toggle() {
        if (Store.running) {
            startService(Intent(this, NetLensVpnService::class.java).setAction(NetLensVpnService.ACTION_STOP))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val p = VpnService.prepare(this)
        if (p != null) vpnLauncher.launch(p) else launchService()
    }

    private fun launchService() {
        val pos = spinner.selectedItemPosition
        val target: String? = if (pos > 0 && pos - 1 < userApps.size) userApps[pos - 1].first else null
        Store.clear()
        val i = Intent(this, NetLensVpnService::class.java)
        i.action = NetLensVpnService.ACTION_START
        if (target != null) i.putExtra(NetLensVpnService.EXTRA_PKG, target)
        ContextCompat.startForegroundService(this, i)
    }

    private fun selectTab(i: Int) {
        tab = i
        for (k in tabButtons.indices) {
            tabButtons[k].setTextColor(if (k == i) cBg else cText)
            tabButtons[k].setBackgroundColor(if (k == i) cAccent else Color.parseColor("#21262D"))
        }
        if (i == 2) loadPerms()
        refresh()
    }

    private fun loadPerms() {
        if (permLoading) return
        permLoading = true
        Thread {
            val r = Perms.scan(packageManager, packageName)
            permRows = r
            permLoading = false
            runOnUiThread { refresh() }
        }.start()
    }

    private fun fmt(n: Long): String = Insights.fmt(n)

    private fun refresh() {
        val running = Store.running
        btnStart.text = if (running) "■ Detener" else "▶ Iniciar captura"
        spinner.isEnabled = !running
        val flows = Store.snapshot()
        status.text = if (running)
            "● Capturando · " + flows.size + " conexiones · " + Store.apps.size + " apps"
        else "○ Detenido · elige una app (o todas) y pulsa Iniciar"
        status.setTextColor(if (running) cOk else cMuted)
        if (paused && tab == 0) return

        val rows = ArrayList<Row>()
        when (tab) {
            0 -> {
                val now = System.currentTimeMillis()
                var shown = 0
                for (f in flows.asReversed()) {
                    if (hideDns && f.proto == "DNS") continue
                    if (shown >= 300) break
                    shown++
                    val notes = synchronized(f.notes) { ArrayList(f.notes) }
                    val color = when {
                        notes.any { it.startsWith("⚠") } -> cBad
                        notes.any { it.startsWith("🎯") } -> cWarn
                        notes.any { it.startsWith("⛔") || it.startsWith("✖") } -> cMuted
                        else -> cText
                    }
                    val ago = (now - f.start) / 1000
                    val line1 = f.proto + " · ↑" + fmt(f.up.get()) + " ↓" + fmt(f.down.get()) + " · hace " + ago + " s" + (if (f.closed) " · cerrada" else "")
                    val line2 = if (notes.isEmpty()) "" else "\n" + notes.take(2).joinToString("\n")
                    rows.add(Row(f.stat.label + " → " + f.name() + ":" + f.dstPort, line1 + line2, color, f))
                }
                if (rows.isEmpty()) {
                    rows.add(Row("Sin tráfico todavía", "Pulsa «Iniciar captura», concede el permiso de VPN y abre otra app. Verás aquí cada conexión, a qué dominio va y qué se puede saber de ella.", cMuted))
                }
            }
            1 -> {
                val stats = Store.apps.values.sortedByDescending { it.up.get() + it.down.get() }
                for (s in stats) {
                    val bad = s.clearFlows.get() > 0
                    val tr = s.trackers.size
                    val color = if (bad) cBad else if (tr > 0) cWarn else cText
                    var sub = "↑" + fmt(s.up.get()) + " ↓" + fmt(s.down.get()) + " · " + s.hosts.size + " dominios · " + s.flows.get() + " conexiones"
                    if (tr > 0) sub += "\n🎯 " + tr + " destinos de anuncios/analítica/fallos"
                    if (bad) sub += "\n⚠ " + s.clearFlows.get() + " conexiones HTTP sin cifrar"
                    rows.add(Row(s.label, sub, color, s))
                }
                if (rows.isEmpty()) rows.add(Row("Aún no hay datos", "Inicia la captura y usa alguna app: aquí verás el resumen por aplicación.", cMuted))
            }
            2 -> {
                rows.add(Row("Esta app (NetLens)", "VPN: la concede el sistema con su diálogo. Notificaciones: toca aquí para pedirlas o abrir los ajustes. Android no permite concederse permisos a sí mismo ni a otras apps en silencio.", cAccent, "self"))
                if (permLoading && permRows.isEmpty()) rows.add(Row("Analizando apps instaladas…", "", cMuted))
                for (p in permRows) {
                    val g = p.granted
                    val color = if (g >= 6) cBad else if (g >= 3) cWarn else cText
                    val names = p.perms.filter { it.second }.map { it.first }
                    var sub = g.toString() + " permisos sensibles concedidos de " + p.perms.size + " declarados"
                    if (names.isNotEmpty()) sub += "\n" + names.take(5).joinToString(", ") + (if (names.size > 5) "…" else "")
                    if (!p.hasInternet) sub += "\nSin permiso INTERNET"
                    rows.add(Row(p.label, sub, color, p))
                }
            }
            else -> {
                for (l in Lessons.ALL) rows.add(Row(l.first, l.second, cText, "lesson"))
            }
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
    }

    private fun onRow(r: Row) {
        val tag = r.tag
        when {
            tag is Flow -> showFlow(tag)
            tag is AppStat -> {
                val pi = Perms.forPackage(packageManager, tag.pkg)
                dialog(tag.label, Insights.forApp(tag, pi), tag.pkg)
            }
            tag is Perms.Info -> {
                val sb = StringBuilder()
                sb.append(tag.pkg).append("\n\n")
                for (p in tag.perms) sb.append(if (p.second) "✔ " else "✖ ").append(p.first).append(if (p.second) "  (concedido)\n" else "  (no concedido)\n")
                if (tag.perms.isEmpty()) sb.append("No declara permisos sensibles.\n")
                sb.append("\n").append(if (tag.hasInternet) "Tiene permiso INTERNET: puede enviar datos fuera del móvil." else "No declara INTERNET: no puede conectarse por sí sola.")
                sb.append("\n\nPara conceder o retirar permisos, abre los ajustes de la app.")
                dialog(tag.label, sb.toString(), tag.pkg)
            }
            tag == "self" -> {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                openSettings(packageName)
            }
            tag == "lesson" -> dialog(r.title, r.sub, null)
        }
    }

    private fun showFlow(f: Flow) {
        val sb = StringBuilder()
        sb.append("Aplicación: ").append(f.stat.label).append("\n")
        sb.append("Destino: ").append(f.dstIp).append(":").append(f.dstPort).append("\n")
        sb.append("Nombre: ").append(f.name()).append("\n")
        sb.append("Protocolo: ").append(f.proto).append("\n")
        sb.append("Subido: ").append(fmt(f.up.get())).append("   Bajado: ").append(fmt(f.down.get())).append("\n")
        sb.append("Duración: ").append((f.last - f.start) / 1000).append(" s\n\n")
        sb.append("Lo que ve el DPI:\n")
        val notes = synchronized(f.notes) { ArrayList(f.notes) }
        if (notes.isEmpty()) sb.append("• (nada que decir de esta conexión)\n")
        for (n in notes) sb.append("• ").append(n).append("\n")
        sb.append("\nLo que NO se ve: si es TLS, el contenido va cifrado. Solo hay destino, nombre, tamaño y momento.")
        dialog("Detalle de la conexión", sb.toString(), null)
    }

    private fun dialog(title: String, msg: String, pkg: String?) {
        val b = AlertDialog.Builder(this)
        b.setTitle(title)
        b.setMessage(msg)
        b.setPositiveButton("Cerrar", null)
        if (pkg != null) b.setNeutralButton("Ajustes de la app") { _, _ -> openSettings(pkg) }
        b.show()
    }

    private fun openSettings(pkg: String) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }
}
