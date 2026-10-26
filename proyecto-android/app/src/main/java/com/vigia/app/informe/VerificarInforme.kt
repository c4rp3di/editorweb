package com.vigia.app.informe

import com.vigia.app.analyze.Severidad
import com.vigia.app.util.Formato
import org.json.JSONObject
import java.security.MessageDigest

/** Abre un informe .vigia dentro de la app: lo descifra, comprueba su firma y resume su contenido. */
object VerificarInforme {

    class Resultado(val abierto: Boolean, val firmaValida: Boolean, val texto: String, val pdf: ByteArray?)

    fun analizar(datos: ByteArray, contrasena: CharArray): Resultado {
        val descifrado = try {
            InformeCifrado.descifrar(datos, contrasena)
        } catch (e: IllegalStateException) {
            return Resultado(false, false, "No se pudo abrir: " + (e.message ?: "contraseña incorrecta"), null)
        } catch (e: IllegalArgumentException) {
            return Resultado(false, false, "No se pudo abrir: " + (e.message ?: "archivo no válido"), null)
        } catch (e: Exception) {
            return Resultado(false, false, "No se pudo abrir: " + e.javaClass.simpleName, null)
        }

        val sobre = try {
            JSONObject(descifrado.sobreJson)
        } catch (e: Exception) {
            return Resultado(true, false, "El archivo se descifró, pero informe.json no es un JSON válido.", descifrado.pdf)
        }
        val v = Firma.verificar(sobre)
        val texto = StringBuilder()
        texto.append("Archivo descifrado correctamente.\n\n")
        texto.append("FIRMA: ").append(if (v.valida) "VÁLIDA" else "NO VÁLIDA (el contenido pudo ser modificado)").append('\n')
        if (v.valida) {
            texto.append("Firmada por: ")
                .append(if (v.esDeEsteTelefono) "este teléfono." else "otra clave (huella " + v.huellaClave + "): el informe no se generó en este teléfono.")
                .append('\n')
        }

        try {
            val contenidoTexto = sobre.getString("contenido")
            val c = JSONObject(contenidoTexto)
            val huella = MessageDigest.getInstance("SHA-256").digest(contenidoTexto.toByteArray(Charsets.UTF_8))
            val sesion = c.optJSONObject("sesion") ?: JSONObject()
            val resumen = c.optJSONObject("resumen") ?: JSONObject()
            texto.append("\nSesión #").append(sesion.optLong("id")).append(" · ")
                .append(Formato.fecha(sesion.optLong("inicio"))).append(" a ").append(Formato.fecha(sesion.optLong("fin"))).append('\n')
            texto.append("Informe generado el ").append(Formato.fecha(c.optLong("generado"))).append('\n')
            texto.append("Críticos ").append(resumen.optInt("criticas")).append(" · Altos ").append(resumen.optInt("altas"))
                .append(" · Medios ").append(resumen.optInt("medias")).append(" · Bajos ").append(resumen.optInt("bajas")).append('\n')
            texto.append("Datos crudos incluidos: ").append(if (c.optBoolean("crudos_incluidos")) "sí" else "no").append('\n')
            val sb = StringBuilder()
            for (i in 0 until 8) sb.append(String.format("%02x", huella[i].toInt() and 0xFF))
            texto.append("Huella del contenido: ").append(sb).append("\n\n")
            texto.append(resumen.optString("texto"))
            val hallazgos = c.optJSONArray("hallazgos")
            if (hallazgos != null && hallazgos.length() > 0) {
                texto.append("\n\nHallazgos:\n")
                for (i in 0 until minOf(hallazgos.length(), 15)) {
                    val h = hallazgos.optJSONObject(i) ?: continue
                    texto.append("• [").append(Severidad.etiqueta(h.optString("severidad"))).append(" · ").append(h.optString("regla"))
                        .append("] ").append(h.optString("titulo")).append('\n')
                }
                if (hallazgos.length() > 15) texto.append("… y ").append(hallazgos.length() - 15).append(" más\n")
            }
        } catch (e: Exception) {
            texto.append("\nNo se pudo leer el resumen del contenido.")
        }
        return Resultado(true, v.valida, texto.toString(), descifrado.pdf)
    }
}
