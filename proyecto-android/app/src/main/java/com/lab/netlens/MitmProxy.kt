package com.lab.netlens

import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.SocketChannel
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

// Objeto para pasar el SNI de forma segura al KeyManager en el mismo hilo
object SniHolder {
    val currentSni = ThreadLocal<String>()
}

class MitmProxy(
    private val port: Int = PORT,
    private val protector: (Socket) -> Boolean
) {
    companion object {
        const val PORT = 8888
        private const val TAG = "MitmProxy"
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
        var sslClient: SSLSocket? = null
        try {
            client.soTimeout = 20000
            // 1. Envolvemos el stream en un PushbackInputStream para poder leer y devolver bytes
            val pushback = PushbackInputStream(client.getInputStream(), 16384)
            
            // 2. Leemos el ClientHello para extraer el SNI
            val peekBuf = ByteArray(16384)
            val n = pushback.read(peekBuf)
            if (n < 6 || (peekBuf[0].toInt() and 0xFF) != 0x16) {
                client.close(); return
            }
            
            val tls = Dpi.tls(peekBuf.copyOf(n))
            val sni = tls?.sni
            if (sni.isNullOrEmpty()) {
                Log.w(TAG, "SNI vacío en ClientHello, cerrando")
                client.close(); return
            }

            // 3. Devolvemos los bytes al stream (unread) para que el SSLSocket los lea intactos
            pushback.unread(peekBuf, 0, n)

            // 4. Pasamos el SNI al KeyManager usando un ThreadLocal
            SniHolder.currentSni.set(sni)
            
            // 5. Creamos un "falso" Socket que lee del PushbackInputStream
            val wrappedSocket = PushbackSocket(client, pushback)
            
            val sslFactory: SSLSocketFactory = sslContext.socketFactory as SSLSocketFactory
            sslClient = sslFactory.createSocket(wrappedSocket, sni, 443, true) as SSLSocket
            sslClient.useClientMode = false
            sslClient.soTimeout = 20000
            sslClient.startHandshake()

            Log.i(TAG, "Cliente TLS OK para $sni")
            handleTlsMitm(sslClient, sni)
        } catch (e: Exception) {
            Log.e(TAG, "handleClient falló: ${e.javaClass.simpleName}: ${e.message}")
            try { sslClient?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        } finally {
            SniHolder.currentSni.remove()
        }
    }

    private fun handleTlsMitm(sslClient: SSLSocket, sni: String) {
        var sslRemote: SSLSocket? = null
        try {
            val remote = Socket()
            protector(remote)
            remote.connect(InetSocketAddress(sni, 443), 10000)
            val defaultFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslRemote = defaultFactory.createSocket(remote, sni, 443, true) as SSLSocket
            sslRemote.startHandshake()
            Log.i(TAG, "Remoto TLS OK para $sni")

            markMitm(sni)

            val in1 = sslClient.inputStream
            val out1 = sslRemote.outputStream
            val in2 = sslRemote.inputStream
            val out2 = sslClient.outputStream

            val t1 = Thread { pipe(in1, out1) }
            val t2 = Thread { pipe(in2, out2) }
            t1.start(); t2.start()
            t1.join(180000); t2.join(180000)
        } catch (e: Exception) {
            Log.w(TAG, "MITM falló para $sni: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { sslClient.close() } catch (_: Exception) {}
            try { sslRemote?.close() } catch (_: Exception) {}
        }
    }

    private fun markMitm(sni: String) {
        try {
            for (f in Store.snapshot()) {
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
        } catch (e: Exception) {
        } finally {
            try { output.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
        }
    }
}

// Envoltorio para engañar al SSLSocket y que lea del PushbackInputStream
class PushbackSocket(private val underlying: Socket, private val pushback: PushbackInputStream) : Socket() {
    override fun getInputStream(): InputStream = pushback
    override fun getOutputStream(): OutputStream = underlying.getOutputStream()
    override fun getInetAddress() = underlying.inetAddress
    override fun getPort() = underlying.port
    override fun getLocalAddress() = underlying.localAddress
    override fun getLocalPort() = underlying.localPort
    override fun getChannel(): SocketChannel? = underlying.channel
    override fun close() = underlying.close()
    override fun isClosed() = underlying.isClosed
    override fun isConnected() = underlying.isConnected
    override fun setSoTimeout(timeout: Int) { underlying.soTimeout = timeout }
    override fun getSoTimeout() = underlying.soTimeout
}

class DynamicSniKeyManager(
    private val certProvider: (String) -> Pair<PrivateKey, X509Certificate>
) : X509ExtendedKeyManager() {

    private val store: KeyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
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
        // Recuperamos el SNI que guardamos antes de crear el socket
        val domain = SniHolder.currentSni.get()
        if (domain.isNullOrEmpty()) {
            Log.e("DynamicSniKeyManager", "No hay SNI en el ThreadLocal")
            return null
        }
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
        } catch (e: Exception) {
            null
        }
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}