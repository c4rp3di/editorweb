package com.vigia.app.integridad

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Atestación de clave por hardware: Android pide al entorno seguro (TEE o StrongBox) que firme una clave nueva y
 * describa en el certificado el estado de arranque del teléfono (arranque verificado, bootloader bloqueado, nivel de
 * parche). Se comprueba que la cadena llega a una raíz de Google conocida y que el reto coincide.
 *
 * Límite que conviene recordar: la comprobación la hace el propio teléfono. Si el sistema estuviera comprometido de forma
 * sofisticada, podría falsear el resultado. Por eso se ofrece guardar la cadena para verificarla en otro equipo.
 */
object Atestacion {
    private const val ALIAS = "vigia_atestacion"
    private const val OID_ATESTACION = "1.3.6.1.4.1.11129.2.1.17"

    /** Huellas SHA-256 de la clave pública de las raíces de atestación publicadas por Google (RSA histórica y ECDSA P-384 desde 2026). */
    private const val RAIZ_RSA = "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae"
    private const val RAIZ_EC = "3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec"
    private const val URL_REVOCACION = "https://android.googleapis.com/attestation/status"

    class Resultado(val error: String?, val cadenaPem: String, val veredicto: String, val texto: String)

    private class Tlv(val clase: Int, val construido: Boolean, val num: Int, val ini: Int, val fin: Int)

    private class Descripcion(
        val version: Long,
        val nivelAtestacion: Int,
        val nivelClave: Int,
        val reto: ByteArray,
        val estadoArranque: Int?,
        val bloqueado: Boolean?,
        val parcheSo: Long?,
        val parcheFabricante: Long?,
        val parcheArranque: Long?
    )

    suspend fun comprobar(): Resultado = withContext(Dispatchers.IO) {
        val reto = ByteArray(32)
        SecureRandom().nextBytes(reto)
        val cadena: List<X509Certificate> = try {
            generar(reto)
        } catch (e: Exception) {
            return@withContext Resultado(
                e.javaClass.simpleName + " " + (e.message ?: ""), "", "NO DISPONIBLE",
                "Este teléfono no ha podido generar una atestación por hardware (" + e.javaClass.simpleName + ")."
            )
        }
        val pem = exportarPem(cadena, reto)
        val texto = StringBuilder()

        // ---- cadena de certificados ----
        var cadenaValida = true
        var motivoCadena = ""
        try {
            for (i in 0 until cadena.size - 1) cadena[i].verify(cadena[i + 1].publicKey)
            val raiz = cadena[cadena.size - 1]
            raiz.verify(raiz.publicKey)
        } catch (e: Exception) {
            cadenaValida = false
            motivoCadena = e.javaClass.simpleName
        }
        val huellaRaiz = hex(MessageDigest.getInstance("SHA-256").digest(cadena[cadena.size - 1].publicKey.encoded))
        val raizRsa = huellaRaiz == RAIZ_RSA
        val raizConocida = raizRsa || huellaRaiz == RAIZ_EC
        if (cadenaValida && !raizRsa) {
            // Con la raíz RSA histórica no se mira la caducidad (Google lo indica así para los teléfonos antiguos).
            try {
                for (c in cadena) c.checkValidity()
            } catch (e: Exception) {
                cadenaValida = false
                motivoCadena = "certificado caducado"
            }
        }

        // ---- descripción de la clave (estado de arranque, parches) ----
        var desc: Descripcion? = null
        var errorDescripcion: String? = null
        try {
            for (i in cadena.size - 1 downTo 0) {
                val extension = cadena[i].getExtensionValue(OID_ATESTACION) ?: continue
                val envoltorio = leer(extension, 0)
                desc = descripcion(extension.copyOfRange(envoltorio.ini, envoltorio.fin))
                break
            }
        } catch (e: Exception) {
            errorDescripcion = e.javaClass.simpleName
        }

        val revocacion = comprobarRevocacion(cadena)

        texto.append("Cadena de certificados: ").append(cadena.size).append(" certificado(s). ")
        texto.append(if (cadenaValida) "Las firmas encadenan correctamente.\n" else "FALLA (" + motivoCadena + ").\n")
        texto.append("Raíz de confianza: ")
            .append(if (raizConocida) "raíz de atestación de Google conocida.\n" else "DESCONOCIDA (no es una raíz de Google; es lo habitual en emuladores o sistemas modificados).\n")
        texto.append("Revocación: ")
            .append(
                when (revocacion) {
                    null -> "no se pudo comprobar (sin conexión).\n"
                    true -> "¡UNA CLAVE DE LA CADENA ESTÁ REVOCADA!\n"
                    else -> "ninguna clave revocada.\n"
                }
            )

        var nivel = -1
        var estado: Int? = null
        var bloqueado: Boolean? = null
        var retoCoincide = false
        val d = desc
        if (d == null) {
            texto.append("\nNo se pudo leer la descripción de la atestación")
                .append(if (errorDescripcion != null) " (" + errorDescripcion + ")" else "").append(".\n")
        } else {
            nivel = d.nivelAtestacion
            estado = d.estadoArranque
            bloqueado = d.bloqueado
            retoCoincide = d.reto.contentEquals(reto)
            texto.append("\nNivel de seguridad: ").append(nombreNivel(d.nivelAtestacion)).append('\n')
            texto.append("Reto de esta comprobación: ").append(if (retoCoincide) "coincide.\n" else "NO coincide (posible respuesta reutilizada).\n")
            texto.append("Estado de arranque: ").append(nombreEstado(d.estadoArranque)).append('\n')
            texto.append("Bootloader: ")
                .append(
                    when (d.bloqueado) {
                        true -> "bloqueado.\n"
                        false -> "DESBLOQUEADO.\n"
                        null -> "no informado.\n"
                    }
                )
            if (d.parcheSo != null) texto.append("Parche de seguridad del sistema: ").append(formatoMes(d.parcheSo)).append('\n')
            if (d.parcheFabricante != null) texto.append("Parche del fabricante: ").append(formatoDia(d.parcheFabricante)).append('\n')
            if (d.parcheArranque != null) texto.append("Parche del arranque: ").append(formatoDia(d.parcheArranque)).append('\n')
        }

        val fiable = raizConocida && cadenaValida && nivel >= 1 && retoCoincide && revocacion != true
        val original = fiable && estado == 0 && bloqueado == true
        val veredicto = if (original) {
            "PARECE ORIGINAL"
        } else if (fiable) {
            "REVISAR"
        } else {
            "NO FIABLE"
        }
        texto.append("\nVeredicto: ").append(veredicto).append(". ")
        texto.append(
            when (veredicto) {
                "PARECE ORIGINAL" -> "El entorno seguro del teléfono asegura que el sistema arrancó verificado y con el bootloader bloqueado."
                "REVISAR" -> "La atestación es auténtica, pero el estado de arranque no es el de un sistema original sin tocar (bootloader desbloqueado o sistema firmado con otra clave)."
                else -> "No se puede confiar en esta atestación (cadena, raíz, nivel o reto no válidos)."
            }
        )
        texto.append("\n\nImportante: esta comprobación la hace el propio teléfono. Si el sistema estuviera comprometido, podría falsearla. ")
        texto.append("Para más garantía, guarda la cadena y verifícala en otro equipo.")
        Resultado(null, pem, veredicto, texto.toString())
    }

    private fun generar(reto: ByteArray): List<X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        val generador = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generador.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(reto)
                .build()
        )
        generador.generateKeyPair()
        val certificados = ks.getCertificateChain(ALIAS) ?: throw IllegalStateException("sin cadena de atestación")
        ks.deleteEntry(ALIAS)
        val lista = ArrayList<X509Certificate>()
        for (c in certificados) lista.add(c as X509Certificate)
        if (lista.isEmpty()) throw IllegalStateException("cadena vacía")
        return lista
    }

    private fun exportarPem(cadena: List<X509Certificate>, reto: ByteArray): String {
        val sb = StringBuilder()
        sb.append("# Cadena de atestación generada por Vigía\n# reto (hex): ").append(hex(reto)).append("\n")
        for (c in cadena) {
            sb.append("-----BEGIN CERTIFICATE-----\n")
            sb.append(Base64.encodeToString(c.encoded, Base64.DEFAULT))
            sb.append("-----END CERTIFICATE-----\n")
        }
        return sb.toString()
    }

    /** true = hay una clave revocada, false = ninguna, null = no se pudo comprobar. */
    private fun comprobarRevocacion(cadena: List<X509Certificate>): Boolean? {
        return try {
            val c = URL(URL_REVOCACION).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "Vigia")
                if (c.responseCode != 200) return null
                val texto = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val entradas = JSONObject(texto).optJSONObject("entries") ?: return null
                for (cert in cadena) {
                    val serie = cert.serialNumber.toString(16).lowercase()
                    if (entradas.has(serie)) return true
                }
                false
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ lector mínimo de DER (ASN.1)

    private fun leer(d: ByteArray, pos: Int): Tlv {
        val b0 = d[pos].toInt() and 0xFF
        val clase = (b0 shr 6) and 3
        val construido = (b0 and 0x20) != 0
        var num = b0 and 0x1F
        var p = pos + 1
        if (num == 0x1F) {
            num = 0
            while (true) {
                val b = d[p].toInt() and 0xFF
                p++
                num = (num shl 7) or (b and 0x7F)
                if ((b and 0x80) == 0) break
            }
        }
        val l = d[p].toInt() and 0xFF
        p++
        var largo = 0
        if ((l and 0x80) == 0) {
            largo = l
        } else {
            val n = l and 0x7F
            for (i in 0 until n) {
                largo = (largo shl 8) or (d[p].toInt() and 0xFF)
                p++
            }
        }
        if (largo < 0 || p + largo > d.size) throw IndexOutOfBoundsException("longitud DER fuera de rango")
        return Tlv(clase, construido, num, p, p + largo)
    }

    private fun hijos(d: ByteArray, t: Tlv): List<Tlv> {
        val res = ArrayList<Tlv>()
        var p = t.ini
        while (p < t.fin) {
            val h = leer(d, p)
            res.add(h)
            p = h.fin
        }
        return res
    }

    private fun entero(d: ByteArray, t: Tlv): Long {
        if (t.fin <= t.ini) return 0L
        var v = if (d[t.ini] < 0) -1L else 0L
        for (i in t.ini until t.fin) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return v
    }

    private fun listaAutorizacion(d: ByteArray, t: Tlv): Map<Int, Tlv> {
        val res = HashMap<Int, Tlv>()
        for (h in hijos(d, t)) {
            if (h.clase == 2 && h.construido && h.ini < h.fin) res[h.num] = leer(d, h.ini)
        }
        return res
    }

    private fun descripcion(kd: ByteArray): Descripcion {
        val raiz = leer(kd, 0)
        val c = hijos(kd, raiz)
        val version = entero(kd, c[0])
        val nivelAtestacion = entero(kd, c[1]).toInt()
        val nivelClave = entero(kd, c[3]).toInt()
        val reto = kd.copyOfRange(c[4].ini, c[4].fin)
        val software = listaAutorizacion(kd, c[6])
        val hardware = listaAutorizacion(kd, c[7])

        var estado: Int? = null
        var bloqueado: Boolean? = null
        val raizConfianza = hardware[704] ?: software[704]
        if (raizConfianza != null) {
            val r = hijos(kd, raizConfianza)
            if (r.size >= 3) {
                bloqueado = kd[r[1].ini].toInt() != 0
                estado = entero(kd, r[2]).toInt()
            }
        }
        fun numero(num: Int): Long? {
            val t = hardware[num] ?: software[num] ?: return null
            return entero(kd, t)
        }
        return Descripcion(version, nivelAtestacion, nivelClave, reto, estado, bloqueado, numero(706), numero(718), numero(719))
    }

    private fun nombreNivel(n: Int): String {
        return when (n) {
            0 -> "SOFTWARE (sin protección de hardware: no fiable)"
            1 -> "entorno seguro del procesador (TEE)"
            2 -> "StrongBox (chip de seguridad independiente)"
            else -> "desconocido (" + n + ")"
        }
    }

    private fun nombreEstado(e: Int?): String {
        return when (e) {
            0 -> "VERIFICADO (sistema original firmado por el fabricante)"
            1 -> "firmado con una clave propia (el usuario instaló su propio sistema)"
            2 -> "NO VERIFICADO (bootloader desbloqueado)"
            3 -> "FALLIDO (la verificación de arranque ha fallado)"
            null -> "no informado"
            else -> "desconocido (" + e + ")"
        }
    }

    private fun formatoMes(v: Long): String {
        val s = v.toString()
        return if (s.length == 6) s.substring(0, 4) + "-" + s.substring(4) else s
    }

    private fun formatoDia(v: Long): String {
        val s = v.toString()
        return if (s.length == 8) s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6) else s
    }

    private fun hex(datos: ByteArray): String {
        val sb = StringBuilder()
        for (b in datos) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }
}
