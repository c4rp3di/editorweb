package com.vigia.app.informe

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Paquete cifrado de informe (.vigia): un ZIP con informe.json (firmado) e informe.pdf, cifrado con AES-256-GCM
 * y una clave derivada de la contraseña del usuario con PBKDF2-HMAC-SHA256.
 *
 * Formato del archivo: «VIGIA1» (6 bytes) · versión de KDF = 1 (1 byte) · iteraciones (4 bytes, big endian) ·
 * sal (16) · IV (12) · texto cifrado con etiqueta GCM al final.
 */
object InformeCifrado {
    private const val ITERACIONES = 310_000
    private val MAGIA = "VIGIA1".toByteArray(Charsets.US_ASCII)

    fun paquete(sobreJson: String, pdf: ByteArray, contrasena: CharArray): ByteArray {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("informe.json"))
            z.write(sobreJson.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("informe.pdf"))
            z.write(pdf)
            z.closeEntry()
        }

        val azar = SecureRandom()
        val sal = ByteArray(16)
        val iv = ByteArray(12)
        azar.nextBytes(sal)
        azar.nextBytes(iv)
        val clave = pbkdf2(contrasena, sal, ITERACIONES)

        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.ENCRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, iv))
        val cifrado = cifrador.doFinal(zip.toByteArray())

        val salida = ByteBuffer.allocate(MAGIA.size + 1 + 4 + sal.size + iv.size + cifrado.size)
        salida.put(MAGIA)
        salida.put(1.toByte())
        salida.putInt(ITERACIONES)
        salida.put(sal)
        salida.put(iv)
        salida.put(cifrado)
        return salida.array()
    }

    /** PBKDF2-HMAC-SHA256 implementado con HMAC estándar (funciona igual en todas las versiones de Android). */
    private fun pbkdf2(contrasena: CharArray, sal: ByteArray, iteraciones: Int): ByteArray {
        val clavePwd = String(contrasena).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(clavePwd, "HmacSHA256"))
        mac.update(sal)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 2..iteraciones) {
            u = mac.doFinal(u)
            for (j in t.indices) {
                t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return t
    }
}
