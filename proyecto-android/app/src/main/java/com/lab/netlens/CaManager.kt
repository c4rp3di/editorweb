package com.lab.netlens

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CaManager {
    private const val TAG = "CaManager"
    private const val CA_ALIAS = "netlens-ca"
    private const val KS_FILE = "netlens-ca.p12"
    private const val KS_PASS = "netlens-ca-password"
    private const val CA_VALIDITY_DAYS = 3650L
    private const val SRV_VALIDITY_DAYS = 365L

    private lateinit var caKey: PrivateKey
    private lateinit var caCert: X509Certificate
    private var initialized = false
    private val serverCerts = ConcurrentHashMap<String, Pair<PrivateKey, X509Certificate>>()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        try {
            val ksFile = File(context.filesDir, KS_FILE)
            val ks = KeyStore.getInstance("PKCS12")
            if (ksFile.exists()) {
                ksFile.inputStream().use { ks.load(it, KS_PASS.toCharArray()) }
                caKey = ks.getKey(CA_ALIAS, KS_PASS.toCharArray()) as PrivateKey
                caCert = ks.getCertificate(CA_ALIAS) as X509Certificate
                Log.i(TAG, "CA cargada desde $ksFile")
            } else {
                val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val now = System.currentTimeMillis()
                val nb = Date(now - TimeUnit.DAYS.toMillis(1))
                val na = Date(now + TimeUnit.DAYS.toMillis(CA_VALIDITY_DAYS))
                val subject = X500Name("CN=NetLens CA, O=NetLens Lab, C=ES")
                val builder = JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(now), nb, na, subject, kp.public)
                builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
                builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
                val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
                caCert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
                caKey = kp.private
                ks.load(null, null)
                ks.setKeyEntry(CA_ALIAS, caKey, KS_PASS.toCharArray(), arrayOf(caCert))
                ksFile.outputStream().use { ks.store(it, KS_PASS.toCharArray()) }
                Log.i(TAG, "CA generada y guardada en $ksFile")
            }
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando CA", e)
        }
    }

    fun getCaCertificate(): X509Certificate = caCert

    fun fingerprint(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(caCert.encoded)
        return d.joinToString(":") { String.format("%02X", it) }
    }

    fun getServerCert(domain: String): Pair<PrivateKey, X509Certificate> {
        return serverCerts.getOrPut(domain) { generateServerCert(domain) }
    }

    private fun generateServerCert(domain: String): Pair<PrivateKey, X509Certificate> {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val nb = Date(now - TimeUnit.DAYS.toMillis(1))
        val na = Date(now + TimeUnit.DAYS.toMillis(SRV_VALIDITY_DAYS))
        val subject = X500Name("CN=$domain")
        val issuer = X500Name(caCert.subjectX500Principal.name)
        val builder = JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(now + domain.hashCode()), nb, na, subject, kp.public)
        builder.addExtension(Extension.basicConstraints, false, BasicConstraints(false))
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, domain)))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(caKey)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return Pair(kp.private, cert)
    }
}
