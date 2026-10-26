package com.vigia.app.integridad

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.vigia.app.collect.inventory.InventarioResumen
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Locale

/** Un punto de la revisión de superficie de ataque: qué se ha mirado, qué se ha visto y dónde se puede cambiar. */
class Punto(
    val titulo: String,
    val detalle: String,
    val nivel: Int,
    val accion: String?,
    val etiquetaAccion: String
) {
    companion object {
        const val OK = 0
        const val REVISAR = 1
        const val AVISO = 2
        const val ACCION_APPS = "vigia:apps"
    }
}

/**
 * Modo de reducción de superficie: revisa el teléfono AHORA (no hace falta sesión) y señala lo que amplía la exposición:
 * accesos especiales, certificados, depuración, parches, bloqueo de pantalla... Solo informa y lleva a los ajustes de
 * Android; no cambia nada por su cuenta.
 */
object Superficie {
    private const val DIA_MS = 86_400_000L

    fun revisar(contexto: Context): List<Punto> {
        val res = ArrayList<Punto>()
        val apps = AppsDispositivo.cargar(contexto)
        val usuario = apps.filter { !it.sistema }

        // ---- bloqueo de pantalla ----
        val seguro = try {
            contexto.getSystemService(KeyguardManager::class.java).isDeviceSecure
        } catch (e: Exception) {
            true
        }
        res.add(
            if (seguro) {
                Punto("Bloqueo de pantalla", "Tienes un bloqueo de pantalla seguro.", Punto.OK, null, "")
            } else {
                Punto(
                    "Sin bloqueo de pantalla seguro",
                    "Cualquiera con el teléfono en la mano puede abrirlo y ver todo. Pon un PIN, patrón o contraseña.",
                    Punto.AVISO, Settings.ACTION_SECURITY_SETTINGS, "Abrir seguridad"
                )
            }
        )

        // ---- parche de seguridad ----
        val dias = diasDelParche()
        if (dias == null) {
            res.add(Punto("Parche de seguridad", "No se pudo leer la fecha del parche.", Punto.OK, null, ""))
        } else if (dias <= 90) {
            res.add(Punto("Parche de seguridad", "Tu parche es reciente (" + Build.VERSION.SECURITY_PATCH + ").", Punto.OK, null, ""))
        } else {
            res.add(
                Punto(
                    "Parche de seguridad antiguo",
                    "Tu parche es de " + Build.VERSION.SECURITY_PATCH + " (hace unos " + dias + " días). Los fallos corregidos después siguen abiertos en tu teléfono.",
                    if (dias > 180) Punto.AVISO else Punto.REVISAR, Settings.ACTION_DEVICE_INFO_SETTINGS, "Buscar actualizaciones"
                )
            )
        }

        // ---- depuración USB ----
        val adb = ajusteGlobal(contexto, Settings.Global.ADB_ENABLED) == 1
        res.add(
            if (adb) {
                Punto(
                    "Depuración USB activada",
                    "Permite controlar el teléfono desde un ordenador conectado. Si no la estás usando, apágala.",
                    Punto.REVISAR, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "Abrir opciones de desarrollador"
                )
            } else {
                Punto("Depuración USB", "Está apagada.", Punto.OK, null, "")
            }
        )

        // ---- certificados instalados por el usuario ----
        val certs = certificadosDeUsuario()
        res.add(
            if (certs > 0) {
                Punto(
                    "Certificados instalados por el usuario",
                    "Hay " + certs + " certificado(s) de confianza añadidos. Pueden permitir leer tu tráfico cifrado. Si no los reconoces, quítalos.",
                    Punto.REVISAR, Settings.ACTION_SECURITY_SETTINGS, "Abrir seguridad"
                )
            } else {
                Punto("Certificados de usuario", "No hay ninguno instalado.", Punto.OK, null, "")
            }
        )

        res.add(listaPunto(
            "Servicios de accesibilidad de apps", usuario.filter { it.accesibilidad },
            "Pueden leer la pantalla y pulsar botones: es lo que más abusan el stalkerware y los troyanos.",
            Settings.ACTION_ACCESSIBILITY_SETTINGS, "Abrir accesibilidad"
        ))
        res.add(listaPunto(
            "Apps que leen tus notificaciones", usuario.filter { it.lectorNotificaciones },
            "Ven los mensajes y códigos de verificación que te llegan.",
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, "Abrir acceso a notificaciones"
        ))
        res.add(listaPunto(
            "Administradores del dispositivo", usuario.filter { it.administrador },
            "Pueden bloquear o borrar el teléfono e impedir que se desinstalen.",
            Settings.ACTION_SECURITY_SETTINGS, "Abrir seguridad"
        ))
        res.add(listaPunto(
            "Teclados de terceros activos", usuario.filter { it.teclado },
            "Un teclado ve todo lo que escribes, contraseñas incluidas.",
            Settings.ACTION_INPUT_METHOD_SETTINGS, "Abrir teclados"
        ))
        res.add(listaPunto(
            "Apps que pueden instalar otras apps", usuario.filter { it.especiales.contains("REQUEST_INSTALL_PACKAGES") },
            "Con esta puerta abierta, una app puede instalar otras sin pasar por una tienda.",
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "Abrir fuentes desconocidas"
        ))
        res.add(listaPunto(
            "Apps que pueden dibujar sobre otras", usuario.filter { it.especiales.contains("SYSTEM_ALERT_WINDOW") },
            "Se usa para superponer pantallas falsas encima de las reales.",
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "Abrir superposición"
        ))
        res.add(listaPunto(
            "Apps instaladas fuera de una tienda conocida", usuario.filter { !InventarioResumen.TIENDAS.contains(it.instalador) },
            "No pasan por los controles de una tienda. Algunas son legítimas (apps de tu empresa, código abierto).",
            Punto.ACCION_APPS, "Abrir el gestor de apps"
        ))
        val revisar = usuario.count { it.nivel == "revisar" }
        res.add(
            if (revisar > 0) {
                Punto(
                    "Apps con puntuación alta de revisión",
                    "Hay " + revisar + " app(s) de usuario que suman varios motivos para mirarlas. La puntuación solo es una guía.",
                    Punto.REVISAR, Punto.ACCION_APPS, "Abrir el gestor de apps"
                )
            } else {
                Punto("Apps con puntuación alta", "Ninguna app de usuario destaca.", Punto.OK, null, "")
            }
        )

        // ---- DNS privado (solo informativo: afecta a la captura de Vigía) ----
        val dnsPrivado = try {
            Settings.Global.getString(contexto.contentResolver, "private_dns_mode") ?: "opportunistic"
        } catch (e: Exception) {
            "opportunistic"
        }
        res.add(
            if (dnsPrivado == "hostname") {
                Punto(
                    "DNS privado fijado a un servidor",
                    "Tienes el DNS privado fijado a un proveedor. Mientras una sesión de Vigía captura el DNS, puede romper la resolución de nombres: usa «Automático» durante las sesiones.",
                    Punto.REVISAR, null, ""
                )
            } else {
                Punto("DNS privado", "Modo «" + dnsPrivado + "»: compatible con la captura de Vigía.", Punto.OK, null, "")
            }
        )
        return res
    }

    private fun listaPunto(titulo: String, apps: List<AppInfo>, porQue: String, accion: String, etiqueta: String): Punto {
        if (apps.isEmpty()) return Punto(titulo, "Ninguna app de usuario.", Punto.OK, null, "")
        val nombres = apps.take(6).joinToString(", ") { it.etiqueta }
        val mas = if (apps.size > 6) " y " + (apps.size - 6) + " más" else ""
        return Punto(titulo, apps.size.toString() + ": " + nombres + mas + ". " + porQue, Punto.REVISAR, accion, etiqueta)
    }

    private fun diasDelParche(): Long? {
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH) ?: return null
            (System.currentTimeMillis() - f.time) / DIA_MS
        } catch (e: Exception) {
            null
        }
    }

    private fun ajusteGlobal(contexto: Context, clave: String): Int {
        return try {
            Settings.Global.getInt(contexto.contentResolver, clave, 0)
        } catch (e: Exception) {
            0
        }
    }

    private fun certificadosDeUsuario(): Int {
        var n = 0
        try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null, null)
            val alias = ks.aliases()
            while (alias.hasMoreElements()) {
                if (alias.nextElement().startsWith("user:")) n++
            }
        } catch (e: Exception) {
        }
        return n
    }
}
