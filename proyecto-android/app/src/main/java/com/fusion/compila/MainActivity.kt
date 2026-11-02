package com.fusion.compila

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private val guardarDocumento = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            if (Build.VERSION.SDK_INT >= 29) {
                Thread {
                    val tmp = archivoPendiente()
                    try {
                        if (!tmp.exists() || tmp.length() == 0L) throw IllegalStateException("no hay datos pendientes: repite la exportación")
                        val valores = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
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
