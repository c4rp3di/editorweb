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
import android.os.Environment
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
import java.io.InputStream

/**
 * Compila: Fusion dentro de un WebView + receptor de .zip.
 *
 * - Fusion (assets/fusion.html, o el archivo que elijas en Ajustes) se sirve desde un origen propio
 *   (https://compila.local/) para que IndexedDB y localStorage se comporten como en un navegador.
 * - Un .zip abierto con «Abrir con» o compartido a la app se guarda en memoria; Fusion lo recoge por el
 *   puente window.AndroidCompila y hace el resto (diagnóstico, mezcla y compilación) con su propio código.
 * - window.AndroidPuente (descargar, descargarBase64, pegar) es el mismo puente que usan los APK de Fusion.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOST = "compila.local"
        private const val URL_FUSION = "https://compila.local/fusion.html"
        private const val ASSET_FUSION = "fusion.html"
        private const val ARCHIVO_FUSION = "fusion-personalizado.html"
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

    private val selectorFusion = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copiarFusion(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        configurarWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Si Fusion tiene algo que cerrar (pantalla de Compilar), lo cierra; si no, sale de la app.
                webView.evaluateJavascript(
                    "(function(){try{return window.__compilaAtras?window.__compilaAtras():false}catch(e){return false}})()"
                ) { resultado ->
                    if (resultado != "true") finish()
                }
            }
        })

        manejarIntent(intent)
        webView.loadUrl(URL_FUSION)
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

        webView.addJavascriptInterface(PuenteDescargas(), "AndroidPuente")
        webView.addJavascriptInterface(PuenteCompila(), "AndroidCompila")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.host == HOST) {
                    val ruta = uri.path ?: "/"
                    return if (ruta == "/" || ruta == "/fusion.html" || ruta == "/index.html") {
                        WebResourceResponse("text/html", "UTF-8", abrirFusion())
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

    /** Fusion personalizado (elegido en Ajustes) si existe; si no, el incluido en la app. */
    private fun abrirFusion(): InputStream {
        val personalizado = File(filesDir, ARCHIVO_FUSION)
        if (personalizado.exists() && personalizado.length() > 0) {
            try {
                return FileInputStream(personalizado)
            } catch (_: Exception) {
            }
        }
        return assets.open(ASSET_FUSION)
    }

    private fun copiarFusion(uri: Uri) {
        try {
            val temporal = File(filesDir, "$ARCHIVO_FUSION.tmp")
            contentResolver.openInputStream(uri)?.use { entrada ->
                temporal.outputStream().use { salida -> entrada.copyTo(salida) }
            } ?: throw IllegalStateException("No se pudo abrir el archivo")
            if (temporal.length() < 100_000L) {
                temporal.delete()
                Toast.makeText(this, "El archivo no parece un Fusion (es demasiado pequeño)", Toast.LENGTH_LONG).show()
                return
            }
            val destino = File(filesDir, ARCHIVO_FUSION)
            destino.delete()
            temporal.renameTo(destino)
            Toast.makeText(this, "Fusion actualizado (" + destino.length() / 1024 + " KB)", Toast.LENGTH_SHORT).show()
            webView.loadUrl(URL_FUSION)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo copiar Fusion: " + e.message, Toast.LENGTH_LONG).show()
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
            // Si Fusion ya está cargado, avisa; si aún arranca, lo recogerá él al terminar de iniciarse.
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

    /** Mismo contrato que el puente de los APK de Fusion: descargar texto, y aquí también binarios y pegar. */
    inner class PuenteDescargas {
        @JavascriptInterface
        fun descargar(nombre: String, contenido: String) {
            guardarEnDescargas(nombre, contenido.toByteArray(Charsets.UTF_8))
        }

        @JavascriptInterface
        fun descargarBase64(nombre: String, base64: String) {
            guardarEnDescargas(nombre, Base64.decode(base64, Base64.DEFAULT))
        }

        @JavascriptInterface
        fun pegar(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) return ""
            return clip.getItemAt(0).coerceToText(this@MainActivity)?.toString() ?: ""
        }
    }

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
        fun elegirFusion() {
            runOnUiThread { selectorFusion.launch(arrayOf("text/html", "*/*")) }
        }

        @JavascriptInterface
        fun restaurarFusion() {
            runOnUiThread {
                File(filesDir, ARCHIVO_FUSION).delete()
                webView.loadUrl(URL_FUSION)
            }
        }

        @JavascriptInterface
        fun info(): String {
            val personalizado = File(filesDir, ARCHIVO_FUSION)
            val texto = if (personalizado.exists() && personalizado.length() > 0) {
                "archivo elegido (" + personalizado.length() / 1024 + " KB)"
            } else {
                "incluido en la app"
            }
            return JSONObject().put("fusion", texto).put("app", "1.0").toString()
        }
    }

    // ---------------------------------------------------------------- Descargas

    private fun guardarEnDescargas(nombre: String, bytes: ByteArray) {
        val limpio = nombre.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        try {
            val destino: String
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val valores = ContentValues()
                valores.put(MediaStore.MediaColumns.DISPLAY_NAME, limpio)
                valores.put(MediaStore.MediaColumns.MIME_TYPE, mimeDe(limpio))
                valores.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                    ?: throw IllegalStateException("No se pudo crear el archivo en Descargas")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                destino = "Descargas/$limpio"
            } else {
                // Antes de Android 10 se evita pedir permisos: se guarda en la carpeta propia de la app.
                val carpeta = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                carpeta.mkdirs()
                File(carpeta, limpio).writeBytes(bytes)
                destino = carpeta.absolutePath + "/" + limpio
            }
            runOnUiThread { Toast.makeText(this, "Guardado en $destino", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "No se pudo guardar: " + e.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun mimeDe(nombre: String): String {
        val n = nombre.lowercase()
        return when {
            n.endsWith(".zip") -> "application/zip"
            n.endsWith(".html") || n.endsWith(".htm") -> "text/html"
            n.endsWith(".json") -> "application/json"
            n.endsWith(".txt") -> "text/plain"
            n.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }
}
