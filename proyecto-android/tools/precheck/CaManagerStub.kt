package com.lab.netlens
import java.security.PrivateKey
import java.security.cert.X509Certificate
object CaManager {
    fun getServerCert(domain: String): Pair<PrivateKey, X509Certificate> = throw UnsupportedOperationException("stub only")
}
