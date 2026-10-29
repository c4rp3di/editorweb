package com.vigia.adbwifi

import android.content.Context
import android.os.Build
import com.vigia.core.seguridad.AlmacenClaves
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Gestor de conexión de libadb-android: aporta la identidad (clave RSA + certificado autofirmado) con la que esta
 * app se presenta a adbd. La clave privada se guarda cifrada con el Keystore (AlmacenClaves); el certificado, que
 * es público, en claro.
 */
internal class GestorAdb private constructor(contexto: Context) : AbsAdbConnectionManager() {

    private val app: Context = contexto.applicationContext
    private val almacen = AlmacenClaves.paraApp(app, "adbwifi")
    private val privada: PrivateKey
    private val certificado: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val archivoClave = File(app.filesDir, "adbwifi_clave.bin")
        val archivoCert = File(app.filesDir, "adbwifi_cert.der")
        var clave: PrivateKey? = null
        var cert: Certificate? = null
        if (archivoClave.exists() && archivoCert.exists()) {
            try {
                val pkcs8 = almacen.desenvolver(archivoClave.readBytes())
                clave = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                pkcs8.fill(0)
                cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(archivoCert.readBytes()))
            } catch (e: Exception) {
                // Si no se puede leer (por ejemplo, el Keystore cambió), se crea una identidad nueva: habrá que volver a emparejar.
                clave = null
                cert = null
            }
        }
        if (clave == null || cert == null) {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val par = gen.generateKeyPair()
            val nuevo = generarCertificado(par)
            archivoClave.writeBytes(almacen.envolver(par.private.encoded))
            archivoCert.writeBytes(nuevo.encoded)
            clave = par.private
            cert = nuevo
        }
        privada = clave!!
        certificado = cert!!
    }

    private fun generarCertificado(par: KeyPair): X509Certificate {
        val desde = Date()
        val hasta = Date(desde.time + 10L * 365L * 24L * 3600L * 1000L)
        val nombre = X500Name("CN=Vigia Suite")
        val serie = BigInteger.valueOf(System.currentTimeMillis())
        val constructor = JcaX509v3CertificateBuilder(nombre, serie, desde, hasta, nombre, par.public)
        val firmante = JcaContentSignerBuilder("SHA256withRSA").build(par.private)
        return JcaX509CertificateConverter().getCertificate(constructor.build(firmante))
    }

    override fun getPrivateKey(): PrivateKey = privada

    override fun getCertificate(): Certificate = certificado

    override fun getDeviceName(): String = "VigiaSuite"

    companion object {
        @Volatile
        private var instancia: GestorAdb? = null

        fun obtener(contexto: Context): GestorAdb {
            val actual = instancia
            if (actual != null) return actual
            synchronized(this) {
                val doble = instancia
                if (doble != null) return doble
                val nuevo = GestorAdb(contexto)
                instancia = nuevo
                return nuevo
            }
        }
    }
}
