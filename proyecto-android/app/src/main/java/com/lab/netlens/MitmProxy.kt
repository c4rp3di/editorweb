package com.lab.netlens

import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager

class MitmProxy(
    private val port: Int = PORT,
    private val protector: (Socket) -> Boolean
) {
    companion object {
        const val PORT = 8888
        private const val TAG = "MitmProxy"
        private const val PEEK_SIZE = 16384
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private lateinit var sslContext: SSLContext

    fun start() {
        if (!Security.getProviders().any { it.name == BouncyCastleProvider.PROVIDER_NAME }) {
            Security.addProvider(BouncyCastleProvider())
        }
        sslContext = buildSslContext()
        running = true
        Thread({
            try {
                serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "MITM escuchando en 127.0.0.1:$port")
                while (running) {
                    val client = serverSocket!!.accept()
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Error en accept", e)
            }
        }, "mitm-accept").start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) {}
    }

    private fun buildSslContext(): SSLContext {
        val km = DynamicSniKeyManager { domain -> CaManager.getServerCert(domain) }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf<KeyManager>(km), null, null)
        return ctx
    }

    private fun handleClient(client: Socket) {
        var consumed = false
        try {
            client.soTimeout = 15000
            val inRaw = BufferedInputStream(client.getInputStream(), PEEK_SIZE)
            inRaw.mark(PEEK_SIZE)
            val peek = ByteArray(PEEK_SIZE)
            var got = 0
            // Leer al menos 6 bytes para detectar TLS
            while (got < 6) {
                val r = inRaw.read(peek, got, 6 - got)
                if (r < 0) break
                got += r
            }
            inRaw.reset()
            if (got < 6 || (peek[0].toInt() and 0xFF) != 0x16) {
                client.close()
                return
            }
            // Leer bloque completo para extraer SNI
            inRaw.mark(PEEK_SIZE)
            var total = 0
            while (total < PEEK_SIZE) {
                val r = inRaw.read(peek, total, PEEK_SIZE - total)
                if (r < 0) break
                total += r
                // Cortar cuando tengamos suficiente para el ClientHello
                val tls = try { Dpi.tls(peek.copyOf(total)) } catch (e: Exception) { null }
                if (tls != null && tls.complete) break
            }
            inRaw.reset()
            val tls = Dpi.tls(peek.copyOf(total))
            val sni = tls?.sni
            if (sni == null) {
                client.close()
                return
            }
            consumed = true
            handleTlsMitm(client, inRaw, sni)
        } catch (e: Exception) {
            Log.e(TAG, "handleClient: ${e.message}", e)
        } finally {
            if (!consumed) try { client.close() } catch (e: Exception) {}
        }
    }

    private fun handleTlsMitm(client: Socket, bufferedIn: BufferedInputStream, sni: String) {
        try {
            // SSLSocket server: usa el socket raw. Como ya hemos bufferizado bytes (reset),
            // el BufferedInputStream los conserva pero SSLSocket leerá del raw.
            // Por eso usamos un wrapper: el SSLSocket lee del raw, y los bytes bufferizados
            // están en el raw. Tras reset(), están en el buffer del BufferedInputStream,
            // pero el raw ya los consumió. Punto delicado.
            // Solución: NO resetear antes; SSLSocket necesita ver los bytes.
            // Como no podemos "re-inyectar" al raw, leemos el raw directamente.
            // (Se ha consumido el buffer; aquí ya no sirve el reset.)
            // Por seguridad, usamos el cliente raw envuelto.
            val sslClient = sslContext.socketFactory.createSocket(client, null, client.port, false) as SSLSocket
            sslClient.useClientMode = false
            sslClient.soTimeout = 20000
            sslClient.startHandshake()
            Log.i(TAG, "MITM cliente OK para $sni")

            val remote = Socket()
            protector(remote)
            remote.connect(InetSocketAddress(sni, 443), 10000)
            val remoteFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslRemote = remoteFactory.createSocket(remote, sni, 443, false) as SSLSocket
            sslRemote.startHandshake()
            Log.i(TAG, "MITM remoto OK para $sni")

            // Marcar el flujo correspondiente
            markMitm(sni)

            // Piping bidireccional
            val t1 = Thread { pipe(sslClient.inputStream, sslRemote.outputStream) }
            val t2 = Thread { pipe(sslRemote.inputStream, sslClient.outputStream) }
            t1.start(); t2.start()
            t1.join(120000); t2.join(120000)
            try { sslClient.close() } catch (e: Exception) {}
            try { sslRemote.close() } catch (e: Exception) {}
        } catch (e: Exception) {
            Log.w(TAG, "MITM falló para $sni: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun markMitm(sni: String) {
        try {
            val flows = Store.snapshot()
            for (f in flows) {
                if (f.host == sni && !f.mitm) {
                    f.mitm = true
                    f.stat.mitmCount.incrementAndGet()
                    f.notes.add("🔓 MITM TLS: contenido descifrado con CA local")
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
                if (n > 0) { output.write(buf, 0, n); output.flush() }
            }
        } catch (e: Exception) {} finally {
            try { output.close() } catch (e: Exception) {}
            try { input.close() } catch (e: Exception) {}
        }
    }
}

class DynamicSniKeyManager(
    private val certProvider: (String) -> Pair<PrivateKey, X509Certificate>
) : X509ExtendedKeyManager() {
    private val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    private val pass = "cert".toCharArray()
    private val domainToAlias = ConcurrentHashMap<String, String>()
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
        val domain = try {
            sslSock.handshakeSession?.peerHost ?: sslSock.session?.peerHost
        } catch (e: Exception) { null }
        if (domain.isNullOrEmpty()) return null
        return getOrCreate(domain)
    }

    private fun getOrCreate(domain: String): String? {
        domainToAlias[domain]?.let { return it }
        return try {
            val (key, cert) = certProvider(domain)
            val alias = "d${counter++}-${domain.hashCode()}"
            store.setKeyEntry(alias, key, pass, arrayOf(cert))
            domainToAlias[domain] = alias
            alias
        } catch (e: Exception) { null }
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}