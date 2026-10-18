package com.carpe.panoptes.modules

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifica la firma ECDSA (P-256, SHA-256) del catálogo con la clave pública fijada en
 * la clave pública que el usuario guarda en Ajustes (auditoría H-02). El SHA-256 de cada ZIP/DEX solo prueba
 * integridad respecto de los hashes que publica el propio catálogo; esta firma es lo que
 * autentica quién publicó esos hashes.
 */
object CatalogSignature {

    /**
     * Genera un par ECDSA P-256. Devuelve (publica X.509 Base64, privada PKCS8 Base64), ambas en una
     * sola línea. La privada NO se guarda en la app: se muestra una vez para copiarla como secret
     * de CI (CATALOG_SIGNING_KEY) y se descarta.
     */
    fun generarPar(): Pair<String, String> {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val par = gen.generateKeyPair()
        return Pair(
            Base64.encodeToString(par.public.encoded, Base64.NO_WRAP),
            Base64.encodeToString(par.private.encoded, Base64.NO_WRAP)
        )
    }

    /** True si el texto es una clave pública EC X.509 en Base64 utilizable. */
    fun claveValida(clavePublicaB64: String): Boolean = try {
        KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(clavePublicaB64.trim(), Base64.DEFAULT)))
        true
    } catch (e: Exception) {
        false
    }

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
