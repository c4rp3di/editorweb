package com.lab.netlens

import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.SocketChannel
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.X509ExtendedKeyManager

class MitmProxy(
    private val port: Int = PORT,
    private val protector: (Socket) -> Boolean
) {
    companion object {
        const val PORT = 8888
        private const val TAG = "MitmProxy"
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var running = false
    private val activeSockets = java.util.Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private lateinit var sslContext: SSLContext
    private lateinit var sniKeyManager: DynamicSniKeyManager

    /** Bind synchronously: the VPN must not route the first TLS flow here until listen() is ready. */
    @Synchronized fun start() {
        if (running) return
        if (!Security.getProviders().any { it.name == BouncyCastleProvider.PROVIDER_NAME }) {
            Security.addProvider(BouncyCastleProvider())
        }
        sslContext = buildSslContext()
        val listener = ServerSocket()
        try {
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port), 50)
        } catch (e: Exception) {
            try { listener.close() } catch (_: Exception) {}
            throw IllegalStateException("No se pudo iniciar el listener MITM en 127.0.0.1:$port", e)
        }
        serverSocket = listener
        running = true
        Log.i(TAG, "MITM escuchando en 127.0.0.1:$port")
        val acceptor = Thread({
            while (running) {
                try {
                    val client = listener.accept()
                    activeSockets.add(client)
                    // stop() may race with accept(); close any late socket rather
                    // than leaving a client alive after activeSockets was drained.
                    if (!running) {
                        activeSockets.remove(client)
                        try { client.close() } catch (_: Exception) {}
                        break
                    }
                    Thread({ handleClient(client) }, "mitm-client").apply { isDaemon = true; start() }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Error en accept", e)
                }
            }
        }, "mitm-accept").apply { isDaemon = true }
        acceptThread = acceptor
        acceptor.start()
    }

    @Synchronized fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        val acceptor = acceptThread
        if (acceptor != null && acceptor !== Thread.currentThread()) {
            try { acceptor.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        // Drain after the acceptor has stopped, so a late accept cannot escape
        // the first pass and leave a socket alive after stop() returns.
        for (socket in activeSockets) try { socket.close() } catch (_: Exception) {}
        activeSockets.clear()
        acceptThread = null
    }

    private fun buildSslContext(): SSLContext {
        val km = DynamicSniKeyManager { domain -> CaManager.getServerCert(domain) }
        sniKeyManager = km
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf<KeyManager>(km), null, null)
        return ctx
    }

    private fun handleClient(client: Socket) {
        var sslClient: SSLSocket? = null
        try {
            client.soTimeout = 15000 // Deadline for the inbound TLS handshake only.
            val sslFactory: SSLSocketFactory = sslContext.socketFactory as SSLSocketFactory
            sslClient = sslFactory.createSocket(client, client.inetAddress.hostAddress, client.port, false) as SSLSocket
            sslClient.useClientMode = false
            sslClient.soTimeout = 15000
            sslClient.startHandshake()
            // Do not close healthy long-lived HTTPS connections after 20 seconds idle.
            sslClient.soTimeout = 0
            client.soTimeout = 0

            // In server mode peerHost is not a reliable source of SNI; use the
            // name parsed from ExtendedSSLSession by the key manager instead.
            val sni = sniKeyManager.serverName(sslClient)
            if (sni.isNullOrEmpty()) {
                Log.w(TAG, "SNI vacío, cerrando")
                return
            }
            Log.i(TAG, "Cliente TLS OK para $sni")
            handleTlsMitm(sslClient, sni)
        } catch (e: Exception) {
            if (running) Log.e(TAG, "handleClient: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { sslClient?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            activeSockets.remove(client)
        }
    }

    private fun handleTlsMitm(sslClient: SSLSocket, sni: String) {
        var sslRemote: SSLSocket? = null
        var rawRemote: Socket? = null
        try {
            val remote = SocketChannel.open().socket()
            rawRemote = remote
            activeSockets.add(remote)
            check(protector(remote)) { "Android no pudo excluir el socket remoto MITM de la VPN; se aborta para evitar un bucle" }
            remote.connect(InetSocketAddress(sni, 443), 10000)
            val defaultFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslRemote = defaultFactory.createSocket(remote, sni, 443, false) as SSLSocket
            // SSLSocket does not necessarily perform HTTPS hostname verification
            // unless endpoint identification is explicitly enabled.
            val remoteParams = sslRemote.sslParameters
            remoteParams.endpointIdentificationAlgorithm = "HTTPS"
            sslRemote.sslParameters = remoteParams
            sslRemote.soTimeout = 15000
            sslRemote.startHandshake()
            sslRemote.soTimeout = 0
            Log.i(TAG, "Remoto TLS OK para $sni")

            markMitm(sni)

            val in1 = sslClient.inputStream
            val out1 = sslRemote.outputStream
            val in2 = sslRemote.inputStream
            val out2 = sslClient.outputStream

            val t1 = Thread { pipe(in1, out1) }
            val t2 = Thread { pipe(in2, out2) }
            t1.isDaemon = true; t2.isDaemon = true
            t1.start(); t2.start()
            // The pipes close their opposite streams when one side ends. Wait for
            // the first termination instead of imposing a 3-minute lifetime cap.
            t1.join()
            try { sslClient.close() } catch (_: Exception) {}
            try { sslRemote.close() } catch (_: Exception) {}
            t2.join(5000)
        } catch (e: Exception) {
            Log.w(TAG, "MITM falló para $sni: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { sslClient.close() } catch (_: Exception) {}
            try { sslRemote?.close() } catch (_: Exception) {}
            try { rawRemote?.close() } catch (_: Exception) {}
            try { rawRemote?.let { activeSockets.remove(it) } } catch (_: Exception) {}
        }
    }

    private fun markMitm(sni: String) {
        try {
            for (f in Store.snapshot()) {
                if (f.host == sni && !f.mitm) {
                    f.mitm = true
                    f.stat.mitmCount.incrementAndGet()
                    f.notes.add("🔓 MITM TLS activo: flujo TLS terminado y reenviado; no filtra rutas ni cuerpos HTTP")
                    break
                }
            }
        } catch (e: Exception) {}
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        try {
            val buf = ByteArray(8192)
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    output.write(buf, 0, n)
                    output.flush()
                }
            }
        } catch (e: Exception) {
        } finally {
            try { output.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
        }
    }
}

class DynamicSniKeyManager(
    private val certProvider: (String) -> Pair<PrivateKey, X509Certificate>
) : X509ExtendedKeyManager() {

    private val store: KeyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    private val pass = "cert".toCharArray()
    private val domainToAlias = ConcurrentHashMap<String, String>()
    private val socketToSni = Collections.synchronizedMap(WeakHashMap<Socket, String>())
    private var counter = 0

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        if (alias == null) return null
        return try { store.getCertificateChain(alias) as Array<X509Certificate>? } catch (e: Exception) { null }
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        if (alias == null) return null
        return try { store.getKey(alias, pass) as PrivateKey? } catch (e: Exception) { null }
    }

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? {
        val sslSock = socket as? SSLSocket ?: return null
        val domain = requestedSni(sslSock) ?: return null
        socketToSni[sslSock] = domain
        return getOrCreate(domain)
    }

    /** Retrieves the actual server_name extension, not the socket's peer address. */
    fun serverName(socket: SSLSocket): String? = socketToSni[socket] ?: requestedSni(socket)

    private fun requestedSni(socket: SSLSocket): String? {
        return try {
            val session = (socket.handshakeSession ?: socket.session) as? ExtendedSSLSession ?: return null
            val requested: List<SNIServerName> = session.requestedServerNames
            val item = requested.firstOrNull { it.type == 0 } ?: return null
            val candidate = if (item is SNIHostName) item.asciiName
                else String(item.encoded, Charsets.US_ASCII)
            normalizeDnsName(candidate)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            // Some Android TLS providers may not expose requestedServerNames.
            null
        }
    }

    private fun normalizeDnsName(value: String): String? {
        val host = value.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (host.isEmpty() || host.length > 253 || host.startsWith('.') || host.endsWith('.')) return null
        val valid = host.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
        return host.takeIf { valid && it.all { c -> c.code < 128 } }
    }

    private fun getOrCreate(domain: String): String? {
        domainToAlias[domain]?.let { return it }
        return synchronized(this) {
            domainToAlias[domain]?.let { return@synchronized it }
            try {
                val (key, cert) = certProvider(domain)
                val alias = "d${counter++}-${domain.hashCode()}"
                store.setKeyEntry(alias, key, pass, arrayOf(cert))
                domainToAlias[domain] = alias
                alias
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}
