package com.vigia.app.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Custodia de la clave de la base de datos.
 *
 * SQLCipher necesita una clave en bytes, y las claves del Android Keystore no se pueden exportar.
 * Solución habitual: se genera una clave aleatoria de 32 bytes para SQLCipher y se guarda
 * CIFRADA (AES-256-GCM) con una clave que nunca sale del Keystore. Sin el Keystore del
 * dispositivo, el fichero guardado no sirve para nada.
 */
object KeyVault {
    private const val ALIAS = "vigia_wrap_key"
    private const val PREFS = "vigia_vault"
    private const val CLAVE_BLOB = "db_key_blob"
    private const val NOMBRE_BD = "vigia.db"

    fun obtenerClaveBd(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val guardado = prefs.getString(CLAVE_BLOB, null)
        if (guardado != null) {
            try {
                return descifrar(Base64.decode(guardado, Base64.NO_WRAP))
            } catch (e: Exception) {
                // El Keystore ya no puede descifrar (p. ej. tras restaurar el teléfono).
                // Los datos de sesión son efímeros: se descarta la base antigua y se crea clave nueva.
                context.deleteDatabase(NOMBRE_BD)
            }
        }
        val nueva = ByteArray(32)
        SecureRandom().nextBytes(nueva)
        val blob = Base64.encodeToString(cifrar(nueva), Base64.NO_WRAP)
        prefs.edit().putString(CLAVE_BLOB, blob).commit()
        return nueva
    }

    /** Guarda un texto secreto (por ejemplo un token) cifrado con la clave del Keystore. */
    fun guardarSecreto(context: Context, nombre: String, valor: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (valor.isEmpty()) {
            prefs.edit().remove("sec_" + nombre).commit()
            return
        }
        val blob = Base64.encodeToString(cifrar(valor.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString("sec_" + nombre, blob).commit()
    }

    fun leerSecreto(context: Context, nombre: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val guardado = prefs.getString("sec_" + nombre, null) ?: return null
        return try {
            String(descifrar(Base64.decode(guardado, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun claveKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existente = ks.getKey(ALIAS, null)
        if (existente is SecretKey) return existente
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun cifrar(datos: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, claveKeystore())
        val iv = cipher.iv
        val cifrado = cipher.doFinal(datos)
        return iv + cifrado
    }

    private fun descifrar(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, 12)
        val cifrado = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, claveKeystore(), GCMParameterSpec(128, iv))
        return cipher.doFinal(cifrado)
    }
}
