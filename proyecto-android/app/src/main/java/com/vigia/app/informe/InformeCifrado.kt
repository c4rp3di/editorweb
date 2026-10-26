package com.vigia.app.informe

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

    class Descifrado(val sobreJson: String, val pdf: ByteArray?)

    /** Abre un .vigia. Lanza IllegalArgumentException si no es un archivo de Vigía y IllegalStateException si la contraseña es incorrecta. */
    fun descifrar(datos: ByteArray, contrasena: CharArray): Descifrado {
        if (datos.size < 6 + 1 + 4 + 16 + 12 + 16) throw IllegalArgumentException("archivo demasiado pequeño")
        for (i in MAGIA.indices) {
            if (datos[i] != MAGIA[i]) throw IllegalArgumentException("no es un informe de Vigía")
        }
        if (datos[6].toInt() != 1) throw IllegalArgumentException("versión de cifrado desconocida")
        val iteraciones = ByteBuffer.wrap(datos, 7, 4).int
        if (iteraciones < 1000 || iteraciones > 5_000_000) throw IllegalArgumentException("parámetros de cifrado no válidos")
        val sal = datos.copyOfRange(11, 27)
        val iv = datos.copyOfRange(27, 39)
        val cifrado = datos.copyOfRange(39, datos.size)
        val clave = pbkdf2(contrasena, sal, iteraciones)
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.DECRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, iv))
        val plano = try {
            cifrador.doFinal(cifrado)
        } catch (e: javax.crypto.BadPaddingException) {
            throw IllegalStateException("contraseña incorrecta o archivo dañado")
        }
        var json: String? = null
        var pdf: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(plano)).use { z ->
            while (true) {
                val entrada = z.nextEntry ?: break
                val salida = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 64 * 1024 * 1024) throw IllegalArgumentException("contenido demasiado grande")
                    salida.write(buf, 0, n)
                }
                if (entrada.name == "informe.json") json = salida.toString("UTF-8")
                if (entrada.name == "informe.pdf") pdf = salida.toByteArray()
            }
        }
        val texto = json ?: throw IllegalArgumentException("el paquete no contiene informe.json")
        return Descifrado(texto, pdf)
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
