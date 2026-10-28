package com.lab.netlens

import android.Manifest
import android.content.ContentValues
import android.content.ClipData
import android.content.ClipboardManager
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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class Row(val title: String, val sub: String, val color: Int, val tag: Any? = null)

class RowAdapter(private val ctx: Context) : BaseAdapter() {
    var rows: List<Row> = emptyList()
    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val lay: LinearLayout
        if (convertView is LinearLayout) lay = convertView
        else {
            lay = LinearLayout(ctx)
            lay.orientation = LinearLayout.VERTICAL
            val pad = (12 * ctx.resources.displayMetrics.density).toInt()
            lay.setPadding(pad, pad - 2, pad, pad - 2)
            val t = TextView(ctx).apply { textSize = 14f; setTypeface(null, Typeface.BOLD) }
            val s = TextView(ctx).apply { textSize = 12f; setTextColor(Color.parseColor("#8B949E")) }
            lay.addView(t); lay.addView(s)
        }
        val r = rows[position]
        (lay.getChildAt(0) as TextView).apply { text = r.title; setTextColor(r.color) }
        (lay.getChildAt(1) as TextView).apply {
            text = r.sub
            visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE
        }
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
    private val cPurple = Color.parseColor("#BC8CFF")

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
    private var exportRaw = false
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                val text = Export.build(Store.snapshot(), exportRaw)
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "CSV exportado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo exportar", Toast.LENGTH_LONG).show()
            }
        }
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        CaManager.init(this)
        NetworkTools.init(this)
        SessionHistory.init(this)
        ListUpdateWorker.configure(this, NetworkTools.autoUpdate)
        buildUi()
        loadUserApps()
        loadPerms()
        selectTab(0)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            fitsSystemWindows = true
            setPadding(dp(10), dp(8), dp(10), 0)
        }
        val title = TextView(this).apply {
            text = "NetLens · observatorio de red"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText)
        }
        val sub = TextView(this).apply {
            text = "Laboratorio didáctico: qué envían tus apps y a dónde. Todo en local."
            textSize = 12f
            setTextColor(cMuted)
        }
        root.addView(title); root.addView(sub)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        btnStart = Button(this).apply { setOnClickListener { toggle() } }
        spinner = Spinner(this)
        row1.addView(btnStart, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cbQuic = CheckBox(this).apply {
            text = "Bloquear QUIC"; setTextColor(cText); textSize = 12f
            setOnCheckedChangeListener { _, on -> Store.blockQuic = on }
        }
        val cbMitm = CheckBox(this).apply {
            text = "MITM TLS"; setTextColor(cPurple); textSize = 12f
            isChecked = Store.mitmEnabled
            var adjusting = false
            setOnCheckedChangeListener { _, on ->
                if (adjusting) return@setOnCheckedChangeListener
                if (Store.running) {
                    // The proxy/listener lifecycle is tied to service startup.
                    // Avoid UI state saying MITM is on when no listener exists.
                    adjusting = true
                    isChecked = Store.mitmEnabled
                    adjusting = false
                    Toast.makeText(this@MainActivity,
                        "Detén NetLens antes de cambiar MITM TLS; vuelve a iniciar para aplicar el modo.",
                        Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                Store.mitmEnabled = on
                if (on) {
                    Toast.makeText(this@MainActivity,
                        "Android no permite confirmar aquí si la CA está instalada. Expórtala, instálala y verifica su huella; MITM es experimental.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
        val cbDns = CheckBox(this).apply {
            text = "Ocultar DNS"; isChecked = true; setTextColor(cText); textSize = 12f
            setOnCheckedChangeListener { _, on -> hideDns = on; refresh() }
        }
        val cbPause = CheckBox(this).apply {
            text = "Pausar"; setTextColor(cText); textSize = 12f
            setOnCheckedChangeListener { _, on -> paused = on }
        }
        row2.addView(cbQuic); row2.addView(cbMitm); row2.addView(cbDns); row2.addView(cbPause)
        root.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnCsv = Button(this).apply {
            text = "CSV"; textSize = 12f
            setOnClickListener { askExport() }
        }
        val btnCa = Button(this).apply {
            text = "Exportar CA"; textSize = 12f; setTextColor(cPurple)
            setOnClickListener { exportCaCert() }
        }
        val btnWho = Button(this).apply {
            text = "Quién"; textSize = 12f; setTextColor(cAccent)
            setOnClickListener { resolveOwners() }
        }
        row3.addView(btnCsv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row3.addView(btnCa, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row3.addView(btnWho, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row3)

        status = TextView(this).apply { textSize = 12f; setTextColor(cMuted) }
        root.addView(status)

        val tools = Button(this).apply {
            text = "PROTECCIÓN · DNS · REGLAS · LABORATORIO"; textSize = 11f
            setOnClickListener { openTools() }
        }
        root.addView(tools)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf("En vivo", "Apps", "Permisos", "Aprende")
        for (i in names.indices) {
            val b = Button(this).apply {
                text = names[i]; isAllCaps = false; textSize = 12f
                setOnClickListener { selectTab(i) }
            }
            tabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tabButtons.add(b)
        }
        root.addView(tabs)

        list = ListView(this).apply {
            setBackgroundColor(cBg)
            divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#21262D"))
            dividerHeight = 1
        }
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
            } catch (e: Exception) {}
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
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val p = VpnService.prepare(this)
        if (p != null) vpnLauncher.launch(p) else launchService()
    }

    private fun launchService() {
        val pos = spinner.selectedItemPosition
        val target: String? = if (pos > 0 && pos - 1 < userApps.size) userApps[pos - 1].first else null
        if (Store.mitmEnabled) {
            if (target == null) {
                Toast.makeText(this, "MITM requiere una app objetivo concreta", Toast.LENGTH_LONG).show()
                return
            }
            AlertDialog.Builder(this).setTitle("MITM de laboratorio")
                .setMessage("Objetivo: " + target + "\nSolo pruebas propias o autorizadas y con datos ficticios. Puede romper conexiones. No evita pinning ni certifica fugas. El filtro HTTPS trabaja por dominio/SNI; MITM sigue siendo experimental y no filtra rutas ni cuerpos HTTP.")
                .setPositiveButton("Iniciar") { _, _ -> startSelectedCapture(target) }
                .setNegativeButton("Cancelar", null).show()
            return
        }
        startSelectedCapture(target)
    }

    private fun startSelectedCapture(target: String?) {
        Store.lastStartError = null
        Store.lastRuntimeError = null
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
            permRows = Perms.scan(packageManager, packageName)
            permLoading = false
            runOnUiThread { refresh() }
        }.start()
    }

    private fun fmt(n: Long): String = Insights.fmt(n)

    private fun riskAlerts(): List<Row> {
        val rows = ArrayList<Row>()
        val permsByPkg = permRows.associateBy { it.pkg }
        for (s in Store.apps.values) {
            val p = permsByPkg[s.pkg] ?: continue
            if (s.trackers.isEmpty()) continue
            val sens = p.perms.filter { it.second }.map { it.first }
            val risky = sens.any { it.startsWith("Ubicación") || it == "Micrófono" || it == "Cámara" || it.contains("contactos") }
            if (!risky) continue
            val sub = "Permisos: " + sens.joinToString(", ") + "\nContacta con: " + s.trackers.take(3).joinToString(", ")
            rows.add(Row("⚠ " + s.label, sub, cBad, s))
        }
        return rows
    }

    private fun refresh() {
        val running = Store.running
        btnStart.text = if (running) "■ Detener" else "▶ Iniciar captura"
        spinner.isEnabled = !running
        val flows = Store.snapshot()
        val runtimeSuffix = Store.lastRuntimeError?.let { " · ⚠ " + it } ?: ""
        status.text = if (running)
            "● Capturando IPv4 · " + flows.size + " conexiones · " + Store.apps.size + " apps" + (if (Store.mitmEnabled) " · MITM ON" else "") + " · IPv6 no inspeccionado" + runtimeSuffix
        else if (!Store.lastStartError.isNullOrBlank())
            "⚠ No se pudo iniciar: " + Store.lastStartError
        else if (!Store.lastRuntimeError.isNullOrBlank())
            "⚠ Último error de red: " + Store.lastRuntimeError
        else "○ Detenido · elige una app (o todas) y pulsa Iniciar"
        status.setTextColor(if (running && Store.lastRuntimeError.isNullOrBlank()) cOk else if (!Store.lastStartError.isNullOrBlank() || !Store.lastRuntimeError.isNullOrBlank()) cBad else cMuted)
        if (paused && tab == 0) return

        val rows = ArrayList<Row>()
        when (tab) {
            0 -> {
                rows.addAll(riskAlerts())
                val now = System.currentTimeMillis()
                var shown = 0
                for (f in flows.asReversed()) {
                    if (hideDns && f.proto == "DNS") continue
                    if (shown >= 300) break
                    shown++
                    val notes = synchronized(f.notes) { ArrayList(f.notes) }
                    val color = when {
                        notes.any { it.startsWith("🔓") } -> cPurple
                        notes.any { it.startsWith("⚠") } -> cBad
                        notes.any { it.startsWith("🎯") } -> cWarn
                        notes.any { it.startsWith("⛔") || it.startsWith("✖") } -> cMuted
                        else -> cText
                    }
                    val ago = (now - f.start) / 1000
                    val line1 = f.proto + " · ↑" + fmt(f.up.get()) + " ↓" + fmt(f.down.get()) + " · hace " + ago + " s" + (if (f.closed) " · cerrada" else "")
                    val line2 = if (notes.isEmpty()) "" else "\n" + notes.take(3).joinToString("\n")
                    rows.add(Row(f.stat.label + " → " + f.name() + ":" + f.dstPort, line1 + line2, color, f))
                }
                if (rows.isEmpty()) rows.add(Row("Sin tráfico todavía", "Pulsa «Iniciar captura», concede el permiso de VPN y abre otra app.", cMuted))
            }
            1 -> {
                val stats = Store.apps.values.sortedByDescending { it.up.get() + it.down.get() }
                for (s in stats) {
                    val bad = s.clearFlows.get() > 0
                    val tr = s.trackers.size
                    val dec = s.mitmCount.get() > 0
                    val color = if (bad) cBad else if (dec) cPurple else if (tr > 0) cWarn else cText
                    var sub = "↑" + fmt(s.up.get()) + " ↓" + fmt(s.down.get()) + " · " + s.hosts.size + " dominios · " + s.flows.get() + " conexiones"
                    if (tr > 0) sub += "\n🎯 " + tr + " destinos de anuncios/analítica/fallos"
                    if (bad) sub += "\n⚠ " + s.clearFlows.get() + " conexiones HTTP sin cifrar"
                    if (dec) sub += "\n🔓 " + s.mitmCount.get() + " conexiones descifradas (MITM)"
                    rows.add(Row(s.label, sub, color, s))
                }
                if (rows.isEmpty()) rows.add(Row("Aún no hay datos", "Inicia la captura y usa alguna app.", cMuted))
            }
            2 -> {
                rows.add(Row("Esta app (NetLens)", "VPN: la concede el sistema. Notificaciones: toca aquí. CA local: expórtala desde el botón «Exportar CA» e instálala en Ajustes → Seguridad.", cAccent, "self"))
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
            else -> for (l in Lessons.ALL) rows.add(Row(l.first, l.second, cText, "lesson"))
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
    }

    private fun onRow(r: Row) {
        val tag = r.tag
        when {
            tag is Flow -> showFlow(tag)
            tag is AppStat -> dialog(tag.label, Insights.forApp(tag, Perms.forPackage(packageManager, tag.pkg)), tag.pkg)
            tag is Perms.Info -> {
                val sb = StringBuilder().append(tag.pkg).append("\n\n")
                for (p in tag.perms) sb.append(if (p.second) "✔ " else "✖ ").append(p.first).append(if (p.second) "  (concedido)\n" else "  (no concedido)\n")
                if (tag.perms.isEmpty()) sb.append("No declara permisos sensibles.\n")
                sb.append("\n").append(if (tag.hasInternet) "Tiene permiso INTERNET: puede enviar datos fuera del móvil." else "No declara INTERNET.")
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

    private fun openTools() {
        val titles=arrayOf("Protección: " + if(NetworkTools.enabled) "ACTIVA" else "INACTIVA", "DNS: ${NetworkTools.dnsMode}", "Reglas guardadas", "Actualizar lista HTTPS", "Estadísticas", "Laboratorio", "Estado y límites", "Actualización periódica", "Historial local", "Borrar historial", "Depuración: ver / copiar log (${DebugLog.count()})")
        AlertDialog.Builder(this).setTitle("NetLens · Herramientas").setItems(titles){_,n -> when(n){
            0 -> {NetworkTools.enabled=!NetworkTools.enabled;DebugLog.log("UI", "Protección/reglas=${NetworkTools.enabled}");openTools()}
            1 -> AlertDialog.Builder(this).setTitle("DNS para consultas UDP/53 capturadas")
                .setItems(arrayOf("normal","DoH","DoT")){_,i -> NetworkTools.dnsMode=arrayOf("normal","DoH","DoT")[i]
                    DebugLog.log("UI", "Modo DNS cambiado a ${NetworkTools.dnsMode}")
                    dialog("DNS", "DoH/DoT usa Cloudflare. No cambia DNS cifrado interno de otras apps. Sin retorno silencioso a DNS en claro si falla. Reinicia captura.",null)}.show()
            2 -> {
                val input=android.widget.EditText(this).apply{setText(NetworkTools.rules.sorted().joinToString("\n"));minLines=5}
                AlertDialog.Builder(this).setTitle("-bloquear / +permitir")
                    .setMessage("Una por línea: -dominio.com, +dominio.com, -paquete.app|dominio.com. Gana la regla coincidente más específica; si empatan, permitir. Activa Protección.")
                    .setView(input).setPositiveButton("Guardar"){_,_->try{NetworkTools.saveRules(input.text.toString())}catch(e:Exception){dialog("Error",e.message ?: "Error",null)}}.setNegativeButton("Cancelar",null).show()
            }
            3 -> {
                val input=android.widget.EditText(this).apply{hint="https://servidor/lista.txt"}
                AlertDialog.Builder(this).setTitle("Suscripción de dominios/hosts")
                    .setMessage("Descarga manual de hasta 4 MiB, solo HTTPS sin redirecciones. Reemplaza la lista anterior solo si es válida. No interpreta EasyList completa. El servidor verá tu IP.")
                    .setView(input).setPositiveButton("Descargar"){_,_->val url=input.text.toString().trim()
                        Thread { val result=try{NetworkTools.updateList(url)}catch(e:Exception){"No actualizado: ${e.message}"}
                            runOnUiThread{dialog("Lista",result,null)} }.start()
                    }.setNegativeButton("Cancelar",null).show()
            }
            4 -> dialog("Estadísticas",NetworkTools.stats(),null)
            5 -> dialog("Laboratorio", "Usa dispositivo/emulador de pruebas y datos ficticios. No crea aislamiento automáticamente.\n\nEn una build propia debuggable, referencia network_security_config desde el manifest y usa: <network-security-config><debug-overrides><trust-anchors><certificates src=\"user\" /></trust-anchors></debug-overrides></network-security-config>. No desactives la validación del servidor.\n\nRelease: revisar permisos, HTTP, validación TLS, secretos y eliminar datos de prueba.\n\nEl filtrado HTTPS añadido funciona por SNI cuando el nombre está visible. MITM sigue experimental y no filtra rutas/cuerpos HTTP; un error TLS no demuestra pinning.",null)
            6 -> dialog("Estado", "Añadido en código: filtrado TCP por SNI TLS antes de reenviar el ClientHello, filtrado HTTP por Host y reglas más específicas, además de DoH/DoT, listas y registro local.\nPendiente: compilación Android y pruebas reales. IPv6 no se inspecciona en esta versión y podría salir fuera de la VPN; no es un cortafuegos integral. ECH puede ocultar SNI. MITM sigue experimental y no filtra rutas ni cuerpos HTTPS. No se certifica ausencia de fugas.",null)
            7 -> {
                if (NetworkTools.savedUrl == null) dialog("Lista", "Primero descarga una lista HTTPS válida.", null)
                else {
                    val choices = arrayOf("Desactivar", "Activar cada 24 h aproximadamente")
                    AlertDialog.Builder(this).setTitle("Actualización periódica").setItems(choices) { _, i ->
                        NetworkTools.autoUpdate = i == 1
                        ListUpdateWorker.configure(this, NetworkTools.autoUpdate)
                        dialog("Actualización", "Estado: ${NetworkTools.autoUpdate}. Android decide el momento exacto según red y restricciones de batería. Última descarga: " +
                            (if(NetworkTools.lastUpdated > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(NetworkTools.lastUpdated)) else "ninguna"), null)
                    }.show()
                }
            }
            8 -> dialog("Historial (últimas 15 de 50)", SessionHistory.recent(), null)
            9 -> AlertDialog.Builder(this).setTitle("¿Borrar historial local?")
                .setMessage("Se eliminarán los resúmenes de sesiones, no las reglas ni listas.")
                .setPositiveButton("Borrar") { _, _ -> SessionHistory.clear() }.setNegativeButton("Cancelar", null).show()
            10 -> showDebugLog()
        }}.show()
    }

    private fun showDebugLog() {
        val statusText = buildString {
            append("Estado actual: VPN=").append(Store.running)
            append("; protección/reglas=").append(NetworkTools.enabled)
            append("; MITM=").append(Store.mitmEnabled)
            append("; bloquearQUIC=").append(Store.blockQuic)
            append("; DNS=").append(NetworkTools.dnsMode)
            append("; flujos=").append(Store.snapshot().size).append("\n")
            append("Último error de inicio: ").append(Store.lastStartError ?: "ninguno").append("\n")
            append("Último error de ejecución: ").append(Store.lastRuntimeError ?: "ninguno").append("\n\n")
            append(DebugLog.snapshot())
        }
        val view = TextView(this).apply {
            text = statusText
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(view)
            layoutParams = android.view.ViewGroup.LayoutParams(-1, dp(420))
        }
        AlertDialog.Builder(this)
            .setTitle("Diagnóstico local · ${DebugLog.count()} eventos")
            .setMessage("El registro incluye destinos IP/dominios, nombres de paquetes y errores, pero no guarda el contenido de los paquetes. Revísalo antes de compartirlo.")
            .setView(scroll)
            .setPositiveButton("Copiar log") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("NetLens diagnóstico", statusText))
                Toast.makeText(this, "Log copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Borrar log") { _, _ ->
                DebugLog.clear()
                Toast.makeText(this, "Log local borrado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun askExport() {
        AlertDialog.Builder(this)
            .setTitle("Exportar sesión a CSV")
            .setItems(arrayOf(
                "Datos brutos (IP, dominio completo, notas del análisis)",
                "Sanitizados (sin IP, dominio base, bytes aproximados, sin notas)"
            )) { _, which ->
                exportRaw = (which == 0)
                createDoc.launch(if (exportRaw) "netlens-bruto.csv" else "netlens-sanitizado.csv")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showFlow(f: Flow) {
        val sb = StringBuilder()
        sb.append("Aplicación: ").append(f.stat.label).append("\n")
        sb.append("Destino: ").append(f.dstIp).append(":").append(f.dstPort).append("\n")
        sb.append("Nombre: ").append(f.name()).append("\n")
        f.org?.let { sb.append("Propietario: ").append(it).append("\n") }
        sb.append("Protocolo: ").append(f.proto).append("\n")
        sb.append("Subido: ").append(fmt(f.up.get())).append("   Bajado: ").append(fmt(f.down.get())).append("\n")
        sb.append("Duración: ").append((f.last - f.start) / 1000).append(" s\n")
        if (f.mitm) sb.append("\n⚠ MITM TLS activo: el proxy terminó TLS en ambos extremos y reenvía el tráfico; no inspecciona rutas ni cuerpos HTTP.\n")
        sb.append("\nLo que ve el DPI:\n")
        val notes = synchronized(f.notes) { ArrayList(f.notes) }
        if (notes.isEmpty()) sb.append("• (nada que decir de esta conexión)\n")
        for (n in notes) sb.append("• ").append(n).append("\n")
        sb.append("\nLímites: sin MITM, el contenido TLS va cifrado. El filtrado HTTPS normal usa el dominio SNI visible; ECH puede ocultarlo. Incluso con MITM, esta versión no filtra rutas ni cuerpos HTTP y no es compatible con todas las apps.")
        dialog("Detalle de la conexión", sb.toString(), null)
    }

    private fun exportCaCert() {
        try {
            val cert = CaManager.getCaCertificate()
            val pem = "-----BEGIN CERTIFICATE-----\n" +
                Base64.encodeToString(cert.encoded, Base64.NO_WRAP) +
                "\n-----END CERTIFICATE-----\n"
            val filename = "netlens-ca.crt"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(pem.toByteArray()) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    Toast.makeText(this, "CA guardada en Descargas como $filename", Toast.LENGTH_LONG).show()
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, filename)
                file.writeText(pem)
                Toast.makeText(this, "CA guardada en ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
            val fp = CaManager.fingerprint()
            AlertDialog.Builder(this)
                .setTitle("Huella de la CA (SHA-256)")
                .setMessage(fp + "\n\nComprueba esta huella tras instalar el certificado en Ajustes → Seguridad → Cifrado y credenciales → Instalar certificado → Certificado de CA.")
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resolveOwners() {
        val ips = Store.snapshot().map { it.dstIp }.toSet()
        if (ips.isEmpty()) { Toast.makeText(this, "Sin conexiones todavía", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Resolviendo propietarios…", Toast.LENGTH_SHORT).show()
        IpInfo.resolve(ips) {
            runOnUiThread {
                refresh()
                Toast.makeText(this, "Propietarios actualizados", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dialog(title: String, msg: String, pkg: String?) {
        val b = AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("Cerrar", null)
        if (pkg != null) b.setNeutralButton("Ajustes de la app") { _, _ -> openSettings(pkg) }
        b.show()
    }

    private fun openSettings(pkg: String) {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))) } catch (e: Exception) {}
    }

    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
}

