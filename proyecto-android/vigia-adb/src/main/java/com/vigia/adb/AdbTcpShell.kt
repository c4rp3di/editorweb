package com.vigia.adb

import android.content.Context
import android.util.Base64
import com.vigia.core.seguridad.AlmacenClaves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.math.BigInteger
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec

/** Fallo de conexión o de autorización con adbd. */
class ErrorAdb(val estado: EstadoShell, mensaje: String) : Exception(mensaje)

/**
 * Cliente ADB propio (protocolo CNXN/AUTH/OPEN/WRTE) contra el `adbd` del propio teléfono por TCP local, sin
 * librerías externas. Requiere que adbd escuche en un puerto TCP (normalmente 5555), lo que se activa una vez
 * con `adb tcpip 5555` desde un PC (o con `setprop persist.adb.tcp.port 5555` desde un shell, para que sobreviva
 * a reinicios). La primera vez, Android muestra «¿Permitir depuración USB?»: marcar «Permitir siempre».
 *
 * No admite la depuración inalámbrica con emparejamiento (exige SPAKE2 y TLS con exportación de claves, que
 * Android no ofrece): para eso está [ShizukuShell].
 */
class AdbTcpShell(
    contexto: Context,
    private val host: String = "127.0.0.1",
    private val puerto: Int = 5555
) : ShellProvider {

    private val app = contexto.applicationContext
    private val almacen = AlmacenClaves.paraApp(app, "adb")
    private val cerrojo = Mutex()
    private var privada: PrivateKey? = null
    private var publicaAdb: ByteArray? = null

    override val nombre: String get() = "ADB local (" + host + ":" + puerto + ")"

    override suspend fun estado(): EstadoShell = withContext(Dispatchers.IO) {
        try {
            conectar(false).socket.close()
            EstadoShell.LISTO
        } catch (e: ErrorAdb) {
            e.estado
        } catch (e: Exception) {
            EstadoShell.NO_ACTIVO
        }
    }

    /**
     * Intenta conectar permitiendo que Android muestre el diálogo de autorización (espera hasta 30 s a que la
     * persona pulse «Permitir»). Devuelve el estado resultante.
     */
    suspend fun autorizar(): EstadoShell = withContext(Dispatchers.IO) {
        try {
            conectar(true).socket.close()
            EstadoShell.LISTO
        } catch (e: ErrorAdb) {
            e.estado
        } catch (e: Exception) {
            EstadoShell.NO_ACTIVO
        }
    }

    override suspend fun ejecutar(comando: String, timeoutMs: Long): ResultadoShell = cerrojo.withLock {
        withContext(Dispatchers.IO) { ejecutarBloqueante(comando, timeoutMs) }
    }

    // ------------------------------------------------------------------ claves

    private fun archivoClave() = File(app.filesDir, "adb_clave.bin")

    @Synchronized
    private fun asegurarClaves() {
        if (privada != null && publicaAdb != null) return
        val f = archivoClave()
        val pkcs8: ByteArray
        if (f.exists()) {
            pkcs8 = almacen.desenvolver(f.readBytes())
        } else {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            pkcs8 = gen.generateKeyPair().private.encoded
            f.writeBytes(almacen.envolver(pkcs8))
        }
        val clave = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        pkcs8.fill(0)
        val crt = clave as RSAPrivateCrtKey
        privada = clave
        publicaAdb = ProtocoloAdb.codificarClavePublica(crt.modulus, crt.publicExponent, "vigia@suite")
    }

    // ------------------------------------------------------------------ conexión

    private class Mensaje(val cmd: Int, val arg0: Int, val arg1: Int, val datos: ByteArray)

    private class Conexion(val socket: Socket) {
        private val entrada = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val salida = BufferedOutputStream(socket.getOutputStream())

        fun enviar(cmd: Int, arg0: Int, arg1: Int, datos: ByteArray) {
            val b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(cmd)
            b.putInt(arg0)
            b.putInt(arg1)
            b.putInt(datos.size)
            b.putInt(ProtocoloAdb.suma(datos))
            b.putInt(cmd xor -1)
            salida.write(b.array())
            salida.write(datos)
            salida.flush()
        }

        fun recibir(): Mensaje {
            val cab = ByteArray(24)
            entrada.readFully(cab)
            val b = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int
            val a0 = b.int
            val a1 = b.int
            val largo = b.int
            b.int
            val magia = b.int
            if (magia != (cmd xor -1)) throw IllegalStateException("mensaje ADB no válido")
            if (largo < 0 || largo > MAX_DATOS) throw IllegalStateException("mensaje ADB demasiado grande")
            val datos = ByteArray(largo)
            if (largo > 0) entrada.readFully(datos)
            return Mensaje(cmd, a0, a1, datos)
        }
    }

    private fun conectar(permitirDialogo: Boolean): Conexion {
        asegurarClaves()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, puerto), 3000)
        } catch (e: ConnectException) {
            socket.close()
            throw ErrorAdb(EstadoShell.NO_ACTIVO, "adbd no escucha en " + host + ":" + puerto)
        } catch (e: SocketTimeoutException) {
            socket.close()
            throw ErrorAdb(EstadoShell.NO_ACTIVO, "adbd no responde en " + host + ":" + puerto)
        }
        socket.tcpNoDelay = true
        socket.soTimeout = if (permitirDialogo) 30000 else 4000
        val c = Conexion(socket)
        try {
            c.enviar(ProtocoloAdb.A_CNXN, ProtocoloAdb.VERSION, MAX_DATOS, "host::\u0000".toByteArray(Charsets.US_ASCII))
            var firmaEnviada = false
            while (true) {
                val m = try {
                    c.recibir()
                } catch (e: SocketTimeoutException) {
                    throw ErrorAdb(EstadoShell.SIN_PERMISO, "El teléfono no confirmó la autorización a tiempo")
                }
                if (m.cmd == ProtocoloAdb.A_CNXN) return c
                if (m.cmd == ProtocoloAdb.A_AUTH && m.arg0 == ProtocoloAdb.AUTH_TOKEN) {
                    if (!firmaEnviada) {
                        firmaEnviada = true
                        c.enviar(ProtocoloAdb.A_AUTH, ProtocoloAdb.AUTH_FIRMA, 0, ProtocoloAdb.firmarToken(privada!!, m.datos))
                    } else {
                        if (!permitirDialogo) throw ErrorAdb(EstadoShell.SIN_PERMISO, "Falta autorizar esta app en el teléfono")
                        c.enviar(ProtocoloAdb.A_AUTH, ProtocoloAdb.AUTH_CLAVE_PUBLICA, 0, publicaAdb!!)
                    }
                }
            }
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (e2: Exception) {
            }
            if (e is ErrorAdb) throw e
            throw ErrorAdb(EstadoShell.NO_ACTIVO, "Fallo al hablar con adbd: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun ejecutarBloqueante(comando: String, timeoutMs: Long): ResultadoShell {
        val c = conectar(false)
        try {
            val fin = System.currentTimeMillis() + timeoutMs
            c.socket.soTimeout = 2000
            // El shell «v1» de adbd mezcla salida y errores y no devuelve código: se añade al final.
            val envuelto = "{ " + comando + "\n} 2>&1; echo \"" + MARCA + ":\$?\""
            c.enviar(ProtocoloAdb.A_OPEN, 1, 0, ("shell:" + envuelto + "\u0000").toByteArray(Charsets.UTF_8))
            var remoto = 0
            val salida = ByteArrayOutputStream()
            var terminado = false
            while (!terminado) {
                if (System.currentTimeMillis() > fin) throw ErrorAdb(EstadoShell.NO_ACTIVO, "El comando superó el tiempo límite")
                val m = try {
                    c.recibir()
                } catch (e: SocketTimeoutException) {
                    continue
                }
                when (m.cmd) {
                    ProtocoloAdb.A_OKAY -> remoto = m.arg0
                    ProtocoloAdb.A_WRTE -> {
                        remoto = m.arg0
                        if (salida.size() < LIMITE_SALIDA) salida.write(m.datos)
                        c.enviar(ProtocoloAdb.A_OKAY, 1, remoto, ByteArray(0))
                    }
                    ProtocoloAdb.A_CLSE -> terminado = true
                    else -> {}
                }
            }
            try {
                c.enviar(ProtocoloAdb.A_CLSE, 1, remoto, ByteArray(0))
            } catch (e: Exception) {
            }
            return ProtocoloAdb.separarCodigo(salida.toString("UTF-8"))
        } finally {
            try {
                c.socket.close()
            } catch (e: Exception) {
            }
        }
    }

    companion object {
        const val MAX_DATOS = 65536
        private const val LIMITE_SALIDA = 2 * 1024 * 1024
        const val MARCA = "__VEC__"
    }
}

/** Piezas del protocolo ADB sin estado, para poder probarlas por separado. */
object ProtocoloAdb {
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val VERSION = 0x01000001
    const val AUTH_TOKEN = 1
    const val AUTH_FIRMA = 2
    const val AUTH_CLAVE_PUBLICA = 3

    /** Prefijo DigestInfo de SHA-1 (PKCS#1 v1.5): adb firma el token de 20 bytes como si ya fuera un hash SHA-1. */
    private val PREFIJO_SHA1 = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    fun suma(datos: ByteArray): Int {
        var s = 0
        for (b in datos) s += b.toInt() and 0xFF
        return s
    }

    fun firmarToken(clave: PrivateKey, token: ByteArray): ByteArray {
        val firma = Signature.getInstance("NONEwithRSA")
        firma.initSign(clave)
        firma.update(PREFIJO_SHA1)
        firma.update(token)
        return firma.sign()
    }

    private fun aLittleEndian(n: BigInteger, largo: Int): ByteArray {
        val grande = n.toByteArray()
        val res = ByteArray(largo)
        var i = 0
        while (i < largo && i < grande.size) {
            res[i] = grande[grande.size - 1 - i]
            i++
        }
        return res
    }

    /**
     * Clave pública en el formato de adb (struct RSAPublicKey de Android, 2048 bits) en base64, seguida de
     * «nombre» y un byte cero: es lo que adbd guarda en adb_keys tras «Permitir siempre».
     */
    fun codificarClavePublica(modulo: BigInteger, exponente: BigInteger, nombre: String): ByteArray {
        val palabras = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(modulo.mod(r32).modInverse(r32)).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(modulo)
        val b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(palabras)
        b.putInt(n0inv)
        b.put(aLittleEndian(modulo, 256))
        b.put(aLittleEndian(rr, 256))
        b.putInt(exponente.toInt())
        val base64 = Base64.encodeToString(b.array(), Base64.NO_WRAP)
        return (base64 + " " + nombre + "\u0000").toByteArray(Charsets.US_ASCII)
    }

    /** Separa el texto de salida del código de salida que se añadió al final con [AdbTcpShell.MARCA]. */
    fun separarCodigo(texto: String): ResultadoShell {
        val i = texto.lastIndexOf(AdbTcpShell.MARCA + ":")
        if (i < 0) return ResultadoShell(-1, texto)
        val resto = texto.substring(i + AdbTcpShell.MARCA.length + 1).trim()
        val codigo = resto.toIntOrNull() ?: -1
        return ResultadoShell(codigo, texto.substring(0, i).trimEnd('\n', '\r') )
    }
}
