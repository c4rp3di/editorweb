package com.vigia.app.analyze.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.vigia.app.analyze.Hallazgo
import com.vigia.app.analyze.Severidad
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.FindingEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Comprobación de la red (anti-MitM). Vigía abre una conexión TLS a unos pocos sitios muy conocidos por cada red
 * disponible (wifi y datos móviles) y compara los certificados. Una red que reescribe el tráfico cifrado suele
 * presentar certificados que no validan, firmados por una autoridad instalada por el usuario, o con un emisor
 * distinto del que se ve por otra red. Se hace el saludo TLS y se cierra: no se envía ningún dato.
 */
object SondaTls {
    private val HOSTS = listOf("www.google.com", "www.cloudflare.com", "github.com", "www.wikipedia.org", "www.microsoft.com")

    class Resultado(
        val host: String,
        val red: String,
        val huella: String,
        val emisor: String,
        val valida: Boolean,
        val anclaUsuario: Boolean,
        val error: String?
    )

    class Informe(val resultados: List<Resultado>, val hallazgos: List<Hallazgo>, val texto: String)

    private class PeticionRed(val red: Network?, val callback: ConnectivityManager.NetworkCallback)

    suspend fun comprobar(contexto: Context): Informe = withContext(Dispatchers.IO) {
        val app = contexto.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val redes = ArrayList<Pair<String, Network>>()
        val nombres = HashSet<String>()
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue
            val nombre = nombreDe(c)
            if (nombres.add(nombre)) redes.add(Pair(nombre, n))
        }
        var peticion: PeticionRed? = null
        if (!nombres.contains("datos móviles")) {
            val obtenida = pedirCelular(cm)
            peticion = obtenida
            val redCelular = obtenida.red
            if (redCelular != null) redes.add(Pair("datos móviles", redCelular))
        }

        val usuario = cargarCaUsuario()
        val resultados = ArrayList<Resultado>()
        try {
            for ((nombre, red) in redes) {
                for (host in HOSTS) {
                    resultados.add(sondear(red, nombre, host, usuario.first, usuario.second))
                }
            }
        } finally {
            val p = peticion
            if (p != null) {
                try {
                    cm.unregisterNetworkCallback(p.callback)
                } catch (e: Exception) {
                }
            }
        }
        analizar(resultados, redes.map { it.first })
    }

    private fun nombreDe(c: NetworkCapabilities): String {
        return when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos móviles"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "otra red"
        }
    }

    /** Pide una red de datos móviles aunque haya wifi, para poder comparar. Se mantiene hasta terminar las pruebas. */
    private suspend fun pedirCelular(cm: ConnectivityManager): PeticionRed {
        val espera = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                espera.complete(network)
            }

            override fun onUnavailable() {
                espera.complete(null)
            }
        }
        var red: Network? = null
        try {
            val peticion = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.requestNetwork(peticion, callback)
            red = withTimeoutOrNull(6000L) { espera.await() }
        } catch (e: Exception) {
            red = null
        }
        return PeticionRed(red, callback)
    }

    private fun confianzaPorDefecto(): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        for (tm in f.trustManagers) {
            if (tm is X509TrustManager) return tm
        }
        throw IllegalStateException("sin gestor de confianza")
    }

    private fun cargarCaUsuario(): Pair<Set<String>, Set<String>> {
        val huellas = HashSet<String>()
        val sujetos = HashSet<String>()
        try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null, null)
            val alias = ks.aliases()
            while (alias.hasMoreElements()) {
                val a = alias.nextElement()
                if (!a.startsWith("user:")) continue
                val cert = ks.getCertificate(a) as? X509Certificate ?: continue
                huellas.add(hex(cert.encoded))
                sujetos.add(cert.subjectX500Principal.name)
            }
        } catch (e: Exception) {
        }
        return Pair(huellas, sujetos)
    }

    private fun hex(datos: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(datos)
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun sondear(red: Network, nombreRed: String, host: String, huellasUsuario: Set<String>, sujetosUsuario: Set<String>): Resultado {
        var cadena: Array<X509Certificate>? = null
        var valida = false
        var errorValidacion: String? = null
        val defecto = confianzaPorDefecto()
        val gestor = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                cadena = chain
                try {
                    defecto.checkServerTrusted(chain, authType)
                    valida = true
                } catch (e: Exception) {
                    valida = false
                    errorValidacion = e.javaClass.simpleName
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = defecto.acceptedIssuers
        }

        var error: String? = null
        val plano: Socket = red.socketFactory.createSocket()
        try {
            val direccion = red.getByName(host)
            plano.connect(InetSocketAddress(direccion, 443), 8000)
            plano.soTimeout = 8000
            val contexto = SSLContext.getInstance("TLS")
            contexto.init(null, arrayOf<TrustManager>(gestor), null)
            val ssl = contexto.socketFactory.createSocket(plano, host, 443, true) as SSLSocket
            ssl.startHandshake()
            ssl.close()
        } catch (e: Exception) {
            error = e.javaClass.simpleName
        } finally {
            try {
                plano.close()
            } catch (e: Exception) {
            }
        }

        val recibida = cadena
        if (recibida == null || recibida.isEmpty()) {
            return Resultado(host, nombreRed, "", "", false, false, error ?: errorValidacion ?: "sin certificado")
        }
        val hoja = recibida[0]
        val ancla = recibida.any { huellasUsuario.contains(hex(it.encoded)) } ||
            sujetosUsuario.contains(recibida[recibida.size - 1].issuerX500Principal.name)
        return Resultado(
            host, nombreRed, hex(hoja.encoded), hoja.issuerX500Principal.name, valida, ancla,
            if (valida) null else errorValidacion
        )
    }

    private fun analizar(resultados: List<Resultado>, redes: List<String>): Informe {
        val hallazgos = ArrayList<Hallazgo>()
        val ahora = System.currentTimeMillis()
        val texto = StringBuilder()

        if (redes.isEmpty()) {
            texto.append("No hay ninguna red con internet para comprobar.")
            return Informe(resultados, hallazgos, texto.toString())
        }
        texto.append("Redes comprobadas: ").append(redes.joinToString(", ")).append(".\n\n")

        for (host in HOSTS) {
            texto.append(host).append(": ")
            val partes = ArrayList<String>()
            for (r in resultados.filter { it.host == host }) {
                val estado = if (r.huella.isEmpty()) "sin respuesta (" + (r.error ?: "?") + ")"
                else if (r.anclaUsuario) "FIRMADO POR CA DE USUARIO"
                else if (!r.valida) "NO VÁLIDO"
                else "correcto"
                partes.add(r.red + " " + estado)
            }
            texto.append(partes.joinToString(" · ")).append('\n')
        }

        // 1) cadenas firmadas por autoridades instaladas por el usuario
        for (red in redes) {
            val malos = resultados.filter { it.red == red && it.anclaUsuario }
            if (malos.isNotEmpty()) {
                hallazgos.add(
                    Hallazgo(
                        ahora, Severidad.ALTA, "red", "MITM", "",
                        "Certificados firmados por una CA instalada por el usuario",
                        "Por la red «" + red + "», " + malos.size + " de " + HOSTS.size + " sitios presentan certificados firmados por una " +
                            "autoridad instalada por el usuario: quien la instaló (tú, tu empresa o un atacante) puede leer ese tráfico cifrado.",
                        emptyList(), 75
                    )
                )
            }
        }

        // 2) cadenas que no validan solo en una red
        for (red in redes) {
            val invalidos = resultados.filter { it.red == red && it.huella.isNotEmpty() && !it.valida && !it.anclaUsuario }
            if (invalidos.isEmpty()) continue
            val validosEnOtra = invalidos.count { inv ->
                resultados.any { it.host == inv.host && it.red != red && it.valida }
            }
            if (validosEnOtra > 0) {
                hallazgos.add(
                    Hallazgo(
                        ahora, Severidad.ALTA, "red", "MITM", "",
                        "Certificados no válidos solo en una red",
                        "Por la red «" + red + "», " + validosEnOtra + " sitio(s) presentan un certificado que no valida y que sí es correcto por " +
                            "otra red. Es la señal típica de una red que intercepta el tráfico cifrado.",
                        emptyList(), 70
                    )
                )
            } else {
                hallazgos.add(
                    Hallazgo(
                        ahora, Severidad.MEDIA, "red", "MITM", "",
                        "Certificados no válidos",
                        "Por la red «" + red + "», " + invalidos.size + " sitio(s) presentan certificados que no validan. Puede deberse a un portal " +
                            "cautivo, a la fecha del teléfono mal puesta o a una red que intercepta el tráfico.",
                        emptyList(), 45
                    )
                )
            }
        }

        // 3) emisores distintos entre redes
        if (redes.size >= 2) {
            var diferencias = 0
            for (host in HOSTS) {
                val emisores = resultados.filter { it.host == host && it.huella.isNotEmpty() }.map { it.emisor }.toSet()
                if (emisores.size > 1) diferencias++
            }
            if (diferencias > 0) {
                hallazgos.add(
                    Hallazgo(
                        ahora, Severidad.MEDIA, "red", "MITM", "",
                        "Emisores de certificado distintos según la red",
                        "En " + diferencias + " sitio(s) el emisor del certificado cambia de una red a otra. A veces es normal (los grandes sitios " +
                            "usan varias autoridades), pero también ocurre cuando una red reescribe el tráfico.",
                        emptyList(), 40
                    )
                )
            }
        } else {
            texto.append("\nSolo se pudo comprobar una red: sin una segunda red no hay con qué comparar los emisores.\n")
        }

        texto.append('\n')
        if (hallazgos.isEmpty()) {
            texto.append("No se ha encontrado nada raro. Esto no demuestra que la red sea segura, solo que estos sitios se ven bien ahora mismo.")
        } else {
            texto.append("Hallazgos: ").append(hallazgos.size)
                .append(". Esto es anómalo, no una prueba de ataque: considera investigar la red.")
        }
        return Informe(resultados, hallazgos, texto.toString())
    }

    /** Guarda los hallazgos de la comprobación en la sesión abierta, para que salgan en el informe. */
    suspend fun guardarHallazgos(contexto: Context, sesionId: Long, informe: Informe) {
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.obtener(contexto.applicationContext).analisisDao()
            val filas = informe.hallazgos.map { h ->
                FindingEntity(
                    sessionId = sesionId, ts = h.ts, severidad = h.severidad, modulo = h.modulo, regla = h.regla,
                    app = h.app, titulo = h.titulo, descripcion = h.descripcion, evidenciaIds = "[]", confianza = h.confianza
                )
            }
            if (filas.isNotEmpty()) dao.insertarFindings(filas)
        }
    }
}
