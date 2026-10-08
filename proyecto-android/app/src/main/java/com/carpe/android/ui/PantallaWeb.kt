package com.carpe.android.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.carpe.android.R

// Pantalla web generada por la funcionalidad "pantalla-web".
// Modo: empaquetado (HTML dentro del APK).
//
// Además del WebViewClient original, incorpora:
//  - WebChromeClient: selector de archivos (input type="file"), peticiones
//    de permiso de la web (getUserMedia) y geolocalización.
//  - DownloadListener: descargas http/https van al DownloadManager.
//  - PuenteNativo expuesto como window.AndroidPuente: dos métodos nativos
//    (pegar, descargar) que el editor y las webs cargadas pueden usar sin
//    depender de navigator.clipboard ni del truco de blob+<a download>, que
//    no funcionan en WebView.
class PantallaWeb : Fragment() {

    private var webView: WebView? = null
    private var ultimaCarga: String = ""
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val lanzadorArchivos = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        val cb = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        val uris = if (resultado.resultCode == Activity.RESULT_OK) {
            resultado.data?.let { d ->
                d.clipData?.let { c -> Array(c.itemCount) { i -> c.getItemAt(i).uri } }
                    ?: d.data?.let { arrayOf(it) }
            }
        } else null
        cb.onReceiveValue(uris)
    }

    // Puente nativo expuesto al JavaScript de la web como window.AndroidPuente.
    // Inner class: usa requireContext() del fragment para acceder al portapapeles
    // y al almacenamiento.
    inner class PuenteNativo {
        @JavascriptInterface
        fun pegar(): String = try {
            val g = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            g.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: ""
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun descargar(nombreArchivo: String, contenido: String) {
            try {
                val bytes = contenido.toByteArray(Charsets.UTF_8)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, nombreArchivo)
                        put(MediaStore.Downloads.MIME_TYPE, "text/html")
                    }
                    val uri = requireContext().contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    uri?.let { requireContext().contentResolver.openOutputStream(it)?.use { o -> o.write(bytes) } }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    java.io.File(dir, nombreArchivo).writeBytes(bytes)
                }
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Guardado: $nombreArchivo", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, contenedor: ViewGroup?, estadoGuardado: Bundle?
    ): View {
        val vista = inflater.inflate(R.layout.fragment_pantalla_web, contenedor, false)
        val wv = vista.findViewById<WebView>(R.id.webViewPantallaWeb)
        webView = wv

        // --- Configuración básica ---
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.setSupportZoom(true)
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.settings.allowFileAccess = true
        // Modo empaquetado: la página vive en file:///android_asset/. Sin
        // esto, la carga de recursos externos (CDNs, fuentes, APIs) desde la
        // página fallaría aunque el HTML los referenciara correctamente.
        @Suppress("DEPRECATION")
        wv.settings.allowUniversalAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        wv.settings.allowFileAccessFromFileURLs = true

        // --- Puente nativo ---
        wv.addJavascriptInterface(PuenteNativo(), "AndroidPuente")

        // --- Clientes ---
        wv.webViewClient = crearWebViewClient()
        wv.webChromeClient = crearWebChromeClient()
        wv.setDownloadListener(crearDownloadListener())

        // --- Botón atrás: si la web puede retroceder, retrocede; si no, sale ---
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val actual = webView
                    if (actual != null && actual.canGoBack()) actual.goBack()
                    else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        cargar()
        return vista
    }

    private fun cargar() {
        ultimaCarga = "file:///android_asset/pantalla-web/index.html"
        webView?.loadUrl(ultimaCarga)
    }

    private fun reintentar() {
        webView?.loadUrl(ultimaCarga)
    }

    private fun crearWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.scheme == "reintentar") {
                    reintentar()
                    return true
                }
                return false
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) mostrarPaginaError()
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) mostrarPaginaError()
            }
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                mostrarPaginaError()
            }
        }
    }

    private fun crearWebChromeClient() = object : WebChromeClient() {
        // <input type="file"> en la web -> selector de archivos del sistema.
        override fun onShowFileChooser(wv: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = cb
            return try { lanzadorArchivos.launch(p.createIntent()); true }
            catch (e: Exception) { filePathCallback = null; false }
        }
        // getUserMedia(camera/mic) desde la web. La app ya declaró CAMERA
        // y RECORD_AUDIO en el manifest; el usuario los habrá aceptado al
        // primer uso de la pantalla.
        override fun onPermissionRequest(request: PermissionRequest) {
            activity?.runOnUiThread { request.grant(request.resources) }
        }
        // navigator.geolocation. La app declaró ACCESS_FINE_LOCATION.
        override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
            cb.invoke(origin, true, false)
        }
    }

    private fun crearDownloadListener() = DownloadListener { url, ua, disp, mime, _ ->
        try {
            val uri = Uri.parse(url)
            if (uri.scheme != "http" && uri.scheme != "https") {
                // blob: y data: no los admite DownloadManager. Para esos
                // casos la web debe usar window.AndroidPuente.descargar(...).
                Toast.makeText(requireContext(), "Usa window.AndroidPuente.descargar(...)", Toast.LENGTH_LONG).show()
                return@DownloadListener
            }
            val nombre = URLUtil.guessFileName(url, disp, mime)
            val req = DownloadManager.Request(uri).apply {
                setMimeType(mime); addRequestHeader("User-Agent", ua)
                setTitle(nombre)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nombre)
            }
            (requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(requireContext(), "Descargando: $nombre", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // HTML local con loadDataWithBaseURL: más simple que una View de error superpuesta.
    private fun mostrarPaginaError() {
        val html = "<html><body style='font-family:sans-serif;text-align:center;padding-top:60px;color:#555;'>" +
            "<p>No se pudo cargar la página. Comprueba tu conexión o la URL.</p>" +
            "<button onclick=\"window.location.href='reintentar://reintentar'\" style='padding:10px 20px;font-size:16px;'>Reintentar</button>" +
            "</body></html>"
        webView?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
