/*
 * Script Frida genérico: desactiva el pinning de certificados más común en
 * Android (TrustManager por defecto + OkHttp CertificatePinner) y registra
 * las URLs que la app visita, para correlacionar con los endpoints
 * estáticos ya detectados en F1 (§6.1 [7] del plan).
 *
 * Técnica estándar y pública — la misma que documenta OWASP MASTG
 * (owasp.org/www-project-mobile-app-security) y que circula en
 * frida-codeshare para "universal Android SSL pinning bypass". No hay
 * nada específico de ninguna app aquí: solo los puntos de enganche
 * genéricos de las APIs estándar de Android/OkHttp.
 *
 * SOLO tiene efecto sobre apps propias o con autorización — ver
 * docs/LEGAL.md / el diálogo de primer uso de la app (F3).
 *
 * Eventos emitidos a Kotlin via send(): {"type": "url"|"pin_bypass", ...}
 */

Java.perform(function () {
    log("[panoptes] script cargado");

    // --- 1) TrustManager por defecto: aceptar cualquier certificado ---
    try {
        var TrustManager = Java.registerClass({
            name: "com.carpe.panoptes.dynamic.generated.NoOpTrustManager",
            implements: [Java.use("javax.net.ssl.X509TrustManager").class],
            methods: {
                checkClientTrusted: function (chain, authType) {},
                checkServerTrusted: function (chain, authType) {
                    send({ type: "pin_bypass", target: "X509TrustManager.checkServerTrusted" });
                },
                getAcceptedIssuers: function () {
                    return [];
                }
            }
        });

        var SSLContext = Java.use("javax.net.ssl.SSLContext");
        SSLContext.init.overload(
            "[Ljavax.net.ssl.KeyManager;", "[Ljavax.net.ssl.TrustManager;", "java.security.SecureRandom"
        ).implementation = function (keyManagers, trustManagers, secureRandom) {
            log("[panoptes] SSLContext.init() interceptado — sustituyendo TrustManager");
            this.init(keyManagers, [TrustManager.$new()], secureRandom);
        };
    } catch (e) {
        log("[panoptes] no se pudo enganchar SSLContext: " + e);
    }

    // --- 2) OkHttp3 CertificatePinner: no-op si la app lo usa ---
    try {
        var CertificatePinner = Java.use("okhttp3.CertificatePinner");
        CertificatePinner.check.overload("java.lang.String", "java.util.List").implementation = function (hostname, certs) {
            send({ type: "pin_bypass", target: "OkHttp3.CertificatePinner.check", hostname: hostname });
        };
    } catch (e) {
        // La app puede no usar OkHttp — no es un error, es lo normal.
    }

    // --- 3) Registro de URLs visitadas (correlación estático-dinámica) ---
    try {
        var URL = Java.use("java.net.URL");
        URL.$init.overload("java.lang.String").implementation = function (spec) {
            send({ type: "url", url: spec, source: "java.net.URL" });
            return this.$init(spec);
        };
    } catch (e) {
        log("[panoptes] no se pudo enganchar java.net.URL: " + e);
    }

    try {
        var OkHttpRequestBuilder = Java.use("okhttp3.Request$Builder");
        OkHttpRequestBuilder.url.overload("java.lang.String").implementation = function (url) {
            send({ type: "url", url: url, source: "okhttp3.Request.Builder" });
            return this.url(url);
        };
    } catch (e) {
        // Igual que arriba: normal si la app no usa OkHttp.
    }

    function log(msg) {
        send({ type: "log", message: msg });
    }
});
