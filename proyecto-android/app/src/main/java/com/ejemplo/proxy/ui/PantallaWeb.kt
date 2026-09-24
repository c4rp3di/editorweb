package com.ejemplo.proxy.ui

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.ejemplo.proxy.R

// Pantalla web generada por la funcionalidad "pantalla-web".
// Modo: empaquetado (HTML dentro del APK).
class PantallaWeb : Fragment() {

    private var webView: WebView? = null
    private var ultimaCarga: String = ""

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

    // Decisión 3: HTML local con loadDataWithBaseURL, más simple que una
    // View de error superpuesta. Cubre onReceivedError, onReceivedHttpError
    // (p.ej. 404) y onReceivedSslError (certificado inválido).
    private fun mostrarPaginaError() {
        val html = "<html><body style='font-family:sans-serif;text-align:center;padding-top:60px;color:#555;'>" +
            "<p>No se pudo cargar la página. Comprueba tu conexión o la URL.</p>" +
            "<button onclick=\"window.location.href='reintentar://reintentar'\" style='padding:10px 20px;font-size:16px;'>Reintentar</button>" +
            "</body></html>"
        webView?.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
