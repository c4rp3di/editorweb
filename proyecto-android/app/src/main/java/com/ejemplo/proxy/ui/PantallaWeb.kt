package com.ejemplo.proxy.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
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
import com.ejemplo.proxy.R

class PantallaWeb : Fragment() {

    private var webView: WebView? = null
    private var ultimaCarga: String = ""
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val lanzadorArchivo = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult
        val uri: Uri? = resultado.data?.data
        if (resultado.resultCode == Activity.RESULT_OK && uri != null) {
            callback.onReceiveValue(arrayOf<Uri>(uri))
        } else {
            callback.onReceiveValue(null)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, contenedor: ViewGroup?, estadoGuardado: Bundle?
    ): View {
        val vista = inflater.inflate(R.layout.fragment_pantalla_web, contenedor, false)
        val wv = vista.findViewById<WebView>(R.id.webViewPantallaWeb)
        webView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.allowUniversalAccessFromFileURLs = true
        wv.settings.allowFileAccessFromFileURLs = true
        wv.webViewClient = crearWebViewClient()
        wv.webChromeClient = crearWebChromeClient()
        wv.addJavascriptInterface(PuenteNativo(), "AndroidPuente")
        wv.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            Toast.makeText(requireContext(), "Descarga no disponible: " + url, Toast.LENGTH_SHORT).show()
        })

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val actual = webView
                if (actual != null && actual.canGoBack()) {
                    actual.goBack()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })

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

    private fun crearWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallbackParam: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = filePathCallbackParam
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                lanzadorArchivo.launch(Intent.createChooser(intent, "Elegir archivo"))
                return true
            }
        }
    }

    private fun mostrarPaginaError() {
        val html = "<html><body style='font-family:sans-serif;text-align:center;padding-top:60px;color:#555;'>" +
            "<p>No se pudo cargar la página. Comprueba tu conexión o la URL.</p>" +
            "<button onclick=\"window.location.href='reintentar://reintentar'\" style='padding:10px 20px;font-size:16px;'>Reintentar</button>" +
            "</body></html>"
        webView?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    inner class PuenteNativo {
        @JavascriptInterface
        fun leerPortapapeles(): String {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        }

        @JavascriptInterface
        fun copiarPortapapeles(texto: String) {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("editor", texto))
        }

        @JavascriptInterface
        fun guardarDescarga(nombre: String, contenidoBase64: String): Boolean {
            return try {
                val bytes = Base64.decode(contenidoBase64, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val valores = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, nombre)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    }
                    val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores) ?: return false
                    requireContext().contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val archivo = java.io.File(dir, nombre)
                    archivo.writeBytes(bytes)
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}