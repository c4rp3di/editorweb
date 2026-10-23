package com.vigia.app.integridad

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.vigia.app.collect.inventory.InventarioResumen
import com.vigia.app.collect.inventory.ModuloInventario
import java.security.MessageDigest

class AppInfo(
    val paquete: String,
    val etiqueta: String,
    val sistema: Boolean,
    val actualizadaDeSistema: Boolean,
    val habilitada: Boolean,
    val version: String,
    val instalador: String,
    val instalada: Long,
    val actualizada: Long,
    val huella: String,
    val permisos: List<String>,
    val especiales: List<String>,
    val conLanzador: Boolean,
    val accesibilidad: Boolean,
    val lectorNotificaciones: Boolean,
    val administrador: Boolean,
    val teclado: Boolean,
    val puntos: Int,
    val motivos: List<String>
) {
    /** normal, atencion o revisar: una guía para ordenar la lista, nunca un veredicto. */
    val nivel: String
        get() = if (puntos >= 6) "revisar" else if (puntos >= 3) "atencion" else "normal"
}

/** Lista en vivo de las apps del teléfono con una puntuación sencilla y visible de «cuánto conviene revisarlas». */
object AppsDispositivo {
    private const val SIETE_DIAS_MS = 7L * 24L * 3_600_000L

    @Suppress("DEPRECATION")
    fun cargar(contexto: Context): List<AppInfo> {
        val pm = contexto.packageManager
        val accesibilidad = paquetesDeAjuste(contexto, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val listeners = paquetesDeAjuste(contexto, "enabled_notification_listeners")
        val teclados = paquetesDeAjuste(contexto, Settings.Secure.ENABLED_INPUT_METHODS)
        val admins = administradores(contexto)
        val ahora = System.currentTimeMillis()

        val lista = ArrayList<AppInfo>()
        for (info in pm.getInstalledApplications(0)) {
            val paquete = info.packageName
            val sistema = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val actualizada = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            var version = ""
            var instalada = 0L
            var ultima = 0L
            var huella = ""
            val permisos = ArrayList<String>()
            val especiales = ArrayList<String>()
            try {
                val firma = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
                val pi = pm.getPackageInfo(paquete, PackageManager.GET_PERMISSIONS or firma)
                version = pi.versionName ?: ""
                instalada = pi.firstInstallTime
                ultima = pi.lastUpdateTime
                huella = huellaFirma(pi)
                val pedidos = pi.requestedPermissions
                val banderas = pi.requestedPermissionsFlags
                if (pedidos != null) {
                    for (i in pedidos.indices) {
                        val corto = pedidos[i].substringAfterLast('.')
                        if (ModuloInventario.PERMISOS_SENSIBLES.contains(corto)) {
                            val concedido = banderas != null && i < banderas.size &&
                                (banderas[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                            if (concedido) permisos.add(corto)
                        } else if (ModuloInventario.PERMISOS_ESPECIALES.contains(corto)) {
                            especiales.add(corto)
                        }
                    }
                }
            } catch (e: Exception) {
            }
            val instalador = instaladorDe(pm, paquete)
            val conLanzador = pm.getLaunchIntentForPackage(paquete) != null
            val acc = accesibilidad.contains(paquete)
            val lis = listeners.contains(paquete)
            val adm = admins.contains(paquete)
            val tec = teclados.contains(paquete)

            // Puntuación visible: cada punto tiene una razón escrita.
            var puntos = 0
            val motivos = ArrayList<String>()
            fun sumar(p: Int, motivo: String) {
                puntos += p
                motivos.add(motivo)
            }
            if (!sistema && !InventarioResumen.TIENDAS.contains(instalador)) sumar(2, "instalada fuera de una tienda conocida")
            if (acc && !sistema) sumar(3, "tiene un servicio de accesibilidad activo")
            if (lis && !sistema) sumar(2, "puede leer tus notificaciones")
            if (adm && !sistema) sumar(3, "es administradora del dispositivo")
            if (tec && !sistema) sumar(2, "es un teclado activo")
            if (permisos.any { it == "READ_SMS" || it == "RECEIVE_SMS" || it == "SEND_SMS" } && !sistema) sumar(2, "tiene permiso de SMS")
            if (permisos.contains("RECORD_AUDIO") && !sistema) sumar(1, "puede usar el micrófono")
            if (permisos.contains("CAMERA") && !sistema) sumar(1, "puede usar la cámara")
            if (permisos.contains("ACCESS_BACKGROUND_LOCATION") && !sistema) sumar(1, "puede ver tu ubicación en segundo plano")
            if (permisos.contains("READ_CALL_LOG") && !sistema) sumar(1, "puede leer el registro de llamadas")
            if (especiales.contains("SYSTEM_ALERT_WINDOW") && !sistema) sumar(1, "puede dibujar sobre otras apps")
            if (especiales.contains("REQUEST_INSTALL_PACKAGES") && !sistema) sumar(1, "puede instalar otras apps")
            if (!sistema && !conLanzador) sumar(2, "no tiene icono para abrirla")
            if (!sistema && instalada > 0L && ahora - instalada < SIETE_DIAS_MS) sumar(1, "se instaló hace menos de una semana")

            lista.add(
                AppInfo(
                    paquete, info.loadLabel(pm).toString(), sistema, actualizada, info.enabled, version, instalador,
                    instalada, ultima, huella, permisos, especiales, conLanzador, acc, lis, adm, tec, puntos, motivos
                )
            )
        }
        lista.sortWith(compareByDescending<AppInfo> { it.puntos }.thenBy { it.etiqueta.lowercase() })
        return lista
    }

    private fun paquetesDeAjuste(contexto: Context, clave: String): Set<String> {
        val res = HashSet<String>()
        try {
            val texto = Settings.Secure.getString(contexto.contentResolver, clave)
            if (texto != null) {
                for (parte in texto.split(':')) {
                    if (parte.isNotBlank()) res.add(parte.substringBefore('/'))
                }
            }
        } catch (e: Exception) {
        }
        return res
    }

    private fun administradores(contexto: Context): Set<String> {
        val res = HashSet<String>()
        try {
            val dpm = contexto.getSystemService(DevicePolicyManager::class.java)
            val admins = dpm.activeAdmins
            if (admins != null) {
                for (a in admins) res.add(a.packageName)
            }
        } catch (e: Exception) {
        }
        return res
    }

    @Suppress("DEPRECATION")
    private fun instaladorDe(pm: PackageManager, paquete: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                pm.getInstallSourceInfo(paquete).installingPackageName ?: ""
            } else {
                pm.getInstallerPackageName(paquete) ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    @Suppress("DEPRECATION")
    private fun huellaFirma(pi: PackageInfo): String {
        return try {
            val firmas = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures
            if (firmas != null && firmas.isNotEmpty()) {
                val d = MessageDigest.getInstance("SHA-256").digest(firmas[0].toByteArray())
                val sb = StringBuilder()
                for (i in 0 until 8) sb.append(String.format("%02x", d[i].toInt() and 0xFF))
                sb.toString()
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}
