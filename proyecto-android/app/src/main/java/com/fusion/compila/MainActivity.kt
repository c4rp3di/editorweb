package com.fusion.compila

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Compila: app dedicada a compilar proyectos Android desde un .zip.
 *
 * - La pantalla (assets/compilar.html, o el archivo que elijas en Ajustes) se sirve desde un origen propio
 *   (https://compila.local/) para que localStorage y fetch se comporten como en un navegador.
 * - Un .zip abierto con «Abrir con» o compartido a la app se guarda en memoria; la pantalla lo recoge por el
 *   puente window.AndroidCompila y hace el resto (diagnóstico, mezcla y compilación en GitHub Actions).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOST = "compila.local"
        private const val URL_APP = "https://compila.local/compilar.html"
        private const val ASSET_HTML = "compilar.html"
        private const val ARCHIVO_HTML = "compilar-personalizado.html"
    }

    private lateinit var webView: WebView

    // Autorrecuperación: si se sirve una pantalla personalizada y no avisa de que arrancó (AndroidCompila.listo()),
    // se borra y se vuelve a la incluida en la app.
    @Volatile private var pantallaListo = false
    private val manejador = Handler(Looper.getMainLooper())

    @Volatile private var zipBytes: ByteArray? = null
    @Volatile private var zipNombre: String = "proyecto.zip"

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val selectorArchivos = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        val uris = WebChromeClient.FileChooserParams.parseResult(resultado.resultCode, resultado.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val selectorHtml = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copiarHtml(uri)
    }

    // Guardado de archivos generados por la pantalla (p. ej. el ZIP para Fusion). Los datos se acumulan en un
    // archivo temporal (no en memoria): mientras el selector del sistema está abierto, la app puede quedar en
    // segundo plano y el sistema recrearla o matarla; con el archivo temporal la escritura final no se pierde
    // (antes quedaba un archivo de 0 B). Se escribe donde el usuario elija con el selector (sin permisos).
    private fun archivoPendiente() = File(cacheDir, "guardado-pendiente.tmp")
    private val guardarDocumento = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val tmp = archivoPendiente()
        if (uri == null) { tmp.delete(); return@registerForActivityResult }
        if (!tmp.exists() || tmp.length() == 0L) {
            Toast.makeText(this, "No quedaron datos pendientes que guardar: repite la exportación", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        try {
            var escritos = 0L
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                FileInputStream(tmp).use { entrada -> escritos = entrada.copyTo(out) }
                out.flush()
                (out as? java.io.FileOutputStream)?.fd?.sync()
            }
            val tam = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            tmp.delete()
            Toast.makeText(this, "Guardado: " + (escritos / 1024) + " KB escritos, " + (if (tam >= 0) (tam / 1024).toString() + " KB en el archivo" else "tamaño no comprobado"), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo guardar: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- Descarga directa de APK (sin pasar por el navegador)

    private val prefsDescargas by lazy { getSharedPreferences("descargas", Context.MODE_PRIVATE) }

    private fun avisarJs(msg: String) {
        runOnUiThread {
            webView.evaluateJavascript("window.__compilaAviso&&window.__compilaAviso(" + JSONObject.quote(msg) + ")", null)
        }
    }

    private fun existeUri(uri: Uri): Boolean = try {
        contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    // Aviso inferior con «Abrir»: lanza el selector normal de Android (sin forzar ninguna app), que ofrece «Solo una vez / Siempre»
    // y recuerda la elección.
    private var pendienteAbrir: Pair<Long, String>? = null

    override fun onResume() {
        super.onResume()
        pendienteAbrir?.let { pendienteAbrir = null; ofrecerAbrir(it.first, it.second) }
    }

    private fun ofrecerAbrir(id: Long, texto: String) {
        runOnUiThread {
            // Si la app está en segundo plano, el aviso se muestra al volver a ella.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { pendienteAbrir = Pair(id, texto); return@runOnUiThread }
            Snackbar.make(findViewById(android.R.id.content), texto, Snackbar.LENGTH_INDEFINITE)
                .setDuration(12000)
                .setAction("INSTALAR") { instalarApk(id) }
                .show()
        }
    }

    // Uri que el instalador pueda leer: el archivo propio de Descargas servido con FileProvider (lo normal); si no, el que da el gestor.
    private fun uriParaAbrir(id: Long, dm: DownloadManager): Uri? {
        try {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
                    val ruta = if (i >= 0) c.getString(i) else null
                    if (ruta != null) {
                        val f = File(ruta)
                        if (f.exists() && f.canRead()) return FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return dm.getUriForDownloadedFile(id)
    }

    // Instalación con el instalador de paquetes de Android (PackageInstaller): no depende de que ninguna app resuelva un «abrir»;
    // el sistema pide confirmar (y, la primera vez, permitir a Compila instalar apps) y los fallos se cuentan en un aviso.
    private val ACCION_INSTALACION by lazy { packageName + ".INSTALACION" }

    private val receptorInstalacion = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val estado = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            when (estado) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirmar = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmar != null) {
                        try { startActivity(confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (e: Exception) { Toast.makeText(this@MainActivity, "No se pudo abrir el instalador: " + (e.message ?: ""), Toast.LENGTH_LONG).show() }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> Toast.makeText(this@MainActivity, "✅ Instalada", Toast.LENGTH_LONG).show()
                PackageInstaller.STATUS_FAILURE_ABORTED -> Toast.makeText(this@MainActivity, "Instalación cancelada", Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(this@MainActivity, "❌ No se pudo instalar (código " + estado + "): " + msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun instalarApk(id: Long) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        try {
            val uri = dm.getUriForDownloadedFile(id)
            var entrada: InputStream? = null
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
                    val ruta = if (i >= 0) c.getString(i) else null
                    if (ruta != null && File(ruta).canRead()) entrada = FileInputStream(File(ruta))
                }
            }
            if (entrada == null && uri != null) entrada = contentResolver.openInputStream(uri)
            val ins = entrada
            if (ins == null) { Toast.makeText(this, "No se encuentra el archivo en Descargas (¿se borró?)", Toast.LENGTH_LONG).show(); return }
            val instalador = packageManager.packageInstaller
            val sid = instalador.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            instalador.openSession(sid).use { sesion ->
                ins.use { origen -> sesion.openWrite("app.apk", 0, -1).use { salida -> origen.copyTo(salida); sesion.fsync(salida) } }
                val pi = PendingIntent.getBroadcast(this, sid, Intent(ACCION_INSTALACION).setPackage(packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                sesion.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo preparar la instalación (" + (e.message ?: "error") + "): se prueba con «Abrir con»", Toast.LENGTH_LONG).show()
            abrirApkDescargado(id)
        }
    }

    // Se lanza el «Abrir con» normal del sistema (sin forzar app): si hay varias apps ofrece «Solo una vez / Siempre»; si solo hay una, abre esa.
    private fun abrirApkDescargado(id: Long) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = uriParaAbrir(id, dm)
        if (uri == null) {
            Toast.makeText(this, "No se encuentra el archivo en Descargas (¿se borró?)", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir: " + (e.message ?: "ninguna app lo admite") + ". Ábrelo desde Descargas.", Toast.LENGTH_LONG).show()
        }
    }

    // Una sola descarga por enlace: si ya se está bajando, no se repite; si ya está en Descargas, se abre ese archivo
    // (instalador) en vez de crear otra copia «nombre (1).apk».
    private fun descargarDirecto(url: String, nombreIn: String) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val nombre = nombreIn.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "app.apk" }
        val previo = prefsDescargas.getLong(url, -1L)
        if (previo != -1L) {
            dm.query(DownloadManager.Query().setFilterById(previo))?.use { c ->
                if (c.moveToFirst()) {
                    val estado = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (estado == DownloadManager.STATUS_RUNNING || estado == DownloadManager.STATUS_PENDING || estado == DownloadManager.STATUS_PAUSED) {
                        avisarJs("⏳ «" + nombre + "» ya se está descargando")
                        return
                    }
                    if (estado == DownloadManager.STATUS_SUCCESSFUL) {
                        val uri = dm.getUriForDownloadedFile(previo)
                        if (uri != null && existeUri(uri)) {
                            ofrecerAbrir(previo, "«" + nombre + "» ya estaba en Descargas")
                            return
                        }
                    }
                }
            }
        }
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(nombre)
                .setDescription("Compila")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombre)
            val id = dm.enqueue(req)
            prefsDescargas.edit().putLong(url, id).putLong("id:" + id, id).putString("n:" + id, nombre).apply()
            avisarJs("⬇️ Descargando «" + nombre + "» en Descargas…")
        } catch (e: Exception) {
            avisarJs("❌ No se pudo descargar aquí (" + (e.message ?: "error") + "): se abre en el navegador")
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
        }
    }

    private val receptorDescargas = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L || !prefsDescargas.contains("id:" + id)) return
            val nombre = prefsDescargas.getString("n:" + id, "APK") ?: "APK"
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val estado = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (estado == DownloadManager.STATUS_SUCCESSFUL) {
                        ofrecerAbrir(id, "«" + nombre + "» descargado")
                    } else avisarJs("❌ Falló la descarga de «" + nombre + "»")
                }
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receptorDescargas) } catch (_: Exception) { }
        try { unregisterReceiver(receptorInstalacion) } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, receptorInstalacion, IntentFilter(packageName + ".INSTALACION"), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, receptorDescargas, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        configurarWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Si la pantalla tiene algo que cerrar (ajustes, recepción…), lo cierra; si no, sale de la app.
                webView.evaluateJavascript(
                    "(function(){try{return window.__compilaAtras?window.__compilaAtras():false}catch(e){return false}})()"
                ) { resultado ->
                    if (resultado != "true") finish()
                }
            }
        })

        manejarIntent(intent)
        webView.loadUrl(URL_APP)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        manejarIntent(intent)
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWebView() {
        val ajustes = webView.settings
        ajustes.javaScriptEnabled = true
        ajustes.domStorageEnabled = true
        ajustes.allowFileAccess = false
        ajustes.allowContentAccess = false
        ajustes.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(PuenteCompila(), "AndroidCompila")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.host == HOST) {
                    val ruta = uri.path ?: "/"
                    return if (ruta == "/" || ruta == "/compilar.html" || ruta == "/index.html") {
                        WebResourceResponse("text/html", "UTF-8", abrirHtml())
                    } else {
                        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), "".byteInputStream())
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                if (uri.host == HOST) return false
                if (uri.scheme == "http" || uri.scheme == "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                    }
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    selectorArchivos.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView,
                url: String,
                message: String,
                defaultValue: String?,
                result: JsPromptResult
            ): Boolean {
                val campo = EditText(this@MainActivity)
                campo.setText(defaultValue ?: "")
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setView(campo)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(campo.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }
    }

    /** compilar.html elegido en Ajustes si existe; si no, el incluido en la app. */
    private fun abrirHtml(): InputStream {
        val personalizado = File(filesDir, ARCHIVO_HTML)
        if (personalizado.exists() && personalizado.length() > 0) {
            try {
                pantallaListo = false
                manejador.postDelayed({
                    if (!pantallaListo && personalizado.exists()) {
                        personalizado.delete()
                        Toast.makeText(this, "La pantalla elegida no arrancó: se restauró la incluida", Toast.LENGTH_LONG).show()
                        webView.loadUrl(URL_APP)
                    }
                }, 8000)
                return FileInputStream(personalizado)
            } catch (_: Exception) {
            }
        }
        return assets.open(ASSET_HTML)
    }

    private fun copiarHtml(uri: Uri) {
        try {
            val temporal = File(filesDir, "$ARCHIVO_HTML.tmp")
            contentResolver.openInputStream(uri)?.use { entrada ->
                temporal.outputStream().use { salida -> entrada.copyTo(salida) }
            } ?: throw IllegalStateException("No se pudo abrir el archivo")
            if (temporal.length() < 20_000L) {
                temporal.delete()
                Toast.makeText(this, "El archivo no parece el compilar.html (es demasiado pequeño)", Toast.LENGTH_LONG).show()
                return
            }
            // Solo se aceptan pantallas de Compila: texto HTML con la marca <meta name="compila-app">. Así un .zip, un APK
            // u otro HTML cualquiera no pueden sustituir la pantalla y dejar la app inservible.
            val cabeza = temporal.readBytes()
            val texto = String(cabeza, Charsets.ISO_8859_1)
            val esZip = cabeza.size > 1 && cabeza[0] == 'P'.code.toByte() && cabeza[1] == 'K'.code.toByte()
            if (esZip || !texto.contains("<html", ignoreCase = true) || !texto.contains("name=\"compila-app\"")) {
                temporal.delete()
                Toast.makeText(this, "Ese archivo no es una pantalla de Compila: no se importó nada", Toast.LENGTH_LONG).show()
                return
            }
            val destino = File(filesDir, ARCHIVO_HTML)
            destino.delete()
            temporal.renameTo(destino)
            Toast.makeText(this, "Compilar actualizado (" + destino.length() / 1024 + " KB)", Toast.LENGTH_SHORT).show()
            webView.loadUrl(URL_APP)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo copiar el archivo: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- ZIP recibido

    private fun manejarIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
            else -> null
        }
        if (uri == null) return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("No se pudo leer el archivo")
            zipBytes = bytes
            zipNombre = nombreDe(uri)
            // Si la pantalla ya está cargada, avisa; si aún arranca, lo recogerá ella al iniciarse.
            webView.evaluateJavascript("window.__compilaNuevoZip&&window.__compilaNuevoZip()", null)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo leer el ZIP: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun nombreDe(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nombre = c.getString(0)
                    if (!nombre.isNullOrBlank()) return nombre
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "proyecto.zip"
    }

    // ---------------------------------------------------------------- Puentes JS

    inner class PuenteCompila {
        @JavascriptInterface
        fun hayZip(): Boolean = zipBytes != null

        @JavascriptInterface
        fun zipInfo(): String =
            JSONObject().put("nombre", zipNombre).put("tamano", zipBytes?.size ?: 0).toString()

        /** Trozo [offset, offset+largo) del ZIP en base64. El llamador usa múltiplos de 3 bytes. */
        @JavascriptInterface
        fun zipTrozo(offset: Int, largo: Int): String {
            val b = zipBytes ?: return ""
            val fin = minOf(b.size, offset + largo)
            if (offset < 0 || offset >= fin) return ""
            return Base64.encodeToString(b, offset, fin - offset, Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun zipConsumido() {
            zipBytes = null
        }

        @JavascriptInterface
        fun cerrarApp() {
            runOnUiThread { finishAndRemoveTask() }
        }

        @JavascriptInterface
        fun descargarApk(url: String, nombre: String) {
            if (!url.startsWith("https://")) return
            runOnUiThread { descargarDirecto(url, nombre) }
        }

        @JavascriptInterface
        fun abrirUrl(url: String) {
            if (!url.startsWith("https://") && !url.startsWith("http://")) return
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun copiarAlPortapapeles(texto: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Compila", texto))
            }
        }

        @JavascriptInterface
        fun compartirUrl(url: String) {
            runOnUiThread {
                val envio = Intent(Intent.ACTION_SEND)
                envio.type = "text/plain"
                envio.putExtra(Intent.EXTRA_TEXT, url)
                startActivity(Intent.createChooser(envio, "Compartir enlace del APK"))
            }
        }

        /** La pantalla avisa de que arrancó bien (cancela la autorrecuperación). */
        @JavascriptInterface
        fun listo() {
            pantallaListo = true
        }

        @JavascriptInterface
        fun guardarInicio() {
            archivoPendiente().delete()
        }

        /** Añade un trozo (base64) al archivo que se va a guardar. */
        @JavascriptInterface
        fun guardarTrozo(base64: String) {
            FileOutputStream(archivoPendiente(), true).use { it.write(Base64.decode(base64, Base64.DEFAULT)) }
        }

        /**
         * Guarda lo acumulado. En Android 10+ se escribe directamente en Descargas con MediaStore (IS_PENDING),
         * así el índice del sistema recibe el tamaño real y los selectores de otras apps (p. ej. el de Fusion)
         * no lo ven como un archivo de 0 B. En versiones anteriores se usa el selector del sistema.
         */
        @JavascriptInterface
        fun guardarFin(nombre: String) {
            guardarFinTipo(nombre, "application/zip")
        }

        /** Igual que guardarFin pero con el tipo MIME real (p. ej. text/plain para un informe .txt). */
        @JavascriptInterface
        fun guardarFinTipo(nombre: String, mime: String) {
            if (Build.VERSION.SDK_INT >= 29) {
                Thread {
                    val tmp = archivoPendiente()
                    try {
                        if (!tmp.exists() || tmp.length() == 0L) throw IllegalStateException("no hay datos pendientes: repite la exportación")
                        val valores = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                            put(MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                            ?: throw IllegalStateException("no se pudo crear el archivo en Descargas")
                        var escritos = 0L
                        contentResolver.openOutputStream(uri, "w")?.use { out ->
                            FileInputStream(tmp).use { entrada -> escritos = entrada.copyTo(out) }
                            out.flush()
                        }
                        val fin = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        contentResolver.update(uri, fin, null, null)
                        tmp.delete()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Guardado en Descargas: " + (escritos / 1024) + " KB", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "No se pudo guardar: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
                return
            }
            runOnUiThread {
                try {
                    guardarDocumento.launch(nombre)
                } catch (e: Exception) {
                    archivoPendiente().delete()
                    Toast.makeText(this@MainActivity, "No se pudo abrir el selector", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun elegirHtml() {
            runOnUiThread { selectorHtml.launch(arrayOf("text/html", "*/*")) }
        }

        @JavascriptInterface
        fun restaurarHtml() {
            runOnUiThread {
                File(filesDir, ARCHIVO_HTML).delete()
                webView.loadUrl(URL_APP)
            }
        }

        @JavascriptInterface
        fun info(): String {
            val personalizado = File(filesDir, ARCHIVO_HTML)
            val texto = if (personalizado.exists() && personalizado.length() > 0) {
                "archivo elegido (" + personalizado.length() / 1024 + " KB)"
            } else {
                "incluido en la app"
            }
            return JSONObject().put("html", texto).put("app", "1.0").toString()
        }
    }
}
