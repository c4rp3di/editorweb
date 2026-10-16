package com.carpe.panoptes.modules

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifica la firma ECDSA (P-256, SHA-256) del catálogo con la clave pública fijada en
 * Config.CATALOG_PUBLIC_KEY_B64 (auditoría H-02). El SHA-256 de cada ZIP/DEX solo prueba
 * integridad respecto de los hashes que publica el propio catálogo; esta firma es lo que
 * autentica quién publicó esos hashes.
 */
object CatalogSignature {

    /** La firma se espera en DER (lo que genera `cryptography`/OpenSSL), codificada en Base64. */
    fun verify(cuerpo: ByteArray, firmaB64: String, clavePublicaB64: String): Boolean {
        return try {
            val clave = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(clavePublicaB64.trim(), Base64.DEFAULT)))
            val firma = Signature.getInstance("SHA256withECDSA")
            firma.initVerify(clave)
            firma.update(cuerpo)
            firma.verify(Base64.decode(firmaB64.trim(), Base64.DEFAULT))
        } catch (e: Exception) {
            false
        }
    }
}
