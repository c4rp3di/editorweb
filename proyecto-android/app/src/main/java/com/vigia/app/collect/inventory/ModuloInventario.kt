package com.vigia.app.collect.inventory

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.vigia.app.collect.EscritorEventos
import com.vigia.app.core.Recoleccion
import com.vigia.app.store.AppDatabase
import com.vigia.app.store.InventarioSnapshotEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest

/**
 * Módulo inventory: «foto» del estado del teléfono al empezar, cada 15 minutos y al terminar.
 * Cada foto se compara con la anterior y los cambios (app nueva, firma cambiada, permiso
 * concedido, accesibilidad activada, certificado nuevo...) se anotan como eventos del sistema.
 * Es pasivo: lee ajustes y listas que Android permite leer; no instala ni cambia nada.
 */
class ModuloInventario(
    private val ctx: Context,
    private val sink: EscritorEventos,
    private val sesionId: Long
) {
    private var job: Job? = null
    private var anterior: String? = null

    fun iniciar(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            try {
                anterior = AppDatabase.obtener(ctx).inventarioDao().ultimo(sesionId)?.payload
            } catch (e: Exception) {
            }
            while (isActive) {
                if (Recoleccion.grabando) tomar()
                delay(INTERVALO_MS)
            }
        }
    }

    /** Para el reloj y toma la foto final. */
    suspend fun detener() {
        job?.cancelAndJoin()
        job = null
        tomar()
    }

    /** Para sin foto final (servicio destruido de golpe). */
    fun cancelar() {
        job?.cancel()
        job = null
    }

    private suspend fun tomar() {
        withContext(Dispatchers.IO) {
            try {
                val nueva = construir()
                val previo = anterior
                if (previo != null) {
                    try {
                        val dif = InventarioDiff.diferencias(JSONObject(previo), nueva)
                        for (par in dif) {
                            sink.sys("inventario:" + par.first, par.second.toString())
                        }
                    } catch (e: Exception) {
                    }
                }
                val texto = nueva.toString()
                val dao = AppDatabase.obtener(ctx).inventarioDao()
                dao.insertar(InventarioSnapshotEntity(sessionId = sesionId, ts = System.currentTimeMillis(), payload = texto))
                dao.podar(sesionId)
                anterior = texto
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ construcción de la foto

    private fun construir(): JSONObject {
        val pm = ctx.packageManager
        val raiz = JSONObject()
        val ahora = System.currentTimeMillis()
        raiz.put("ts", ahora)
        raiz.put("arranque", ahora - SystemClock.elapsedRealtime())
        raiz.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")

        val apps = JSONArray()
        for (info in pm.getInstalledApplications(0)) {
            apps.put(datosApp(pm, info))
        }
        raiz.put("apps", apps)

        raiz.put("accesibilidad", listaAjuste(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        raiz.put("listeners", listaAjuste("enabled_notification_listeners"))
        raiz.put("teclados", listaAjuste(Settings.Secure.ENABLED_INPUT_METHODS))
        raiz.put("admins", administradores())
        raiz.put("certs", certificadosDeUsuario())
        raiz.put("adb", ajusteGlobal(Settings.Global.ADB_ENABLED))
        raiz.put("dev", ajusteGlobal(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
        return raiz
    }

    @Suppress("DEPRECATION")
    private fun datosApp(pm: PackageManager, info: ApplicationInfo): JSONObject {
        val o = JSONObject()
        val paquete = info.packageName
        o.put("p", paquete)
        val sistema = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        o.put("s", if (sistema) 1 else 0)
        if (!sistema) o.put("i", instalador(pm, paquete))
        try {
            val firma = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val pi = pm.getPackageInfo(paquete, PackageManager.GET_PERMISSIONS or firma)
            o.put("v", pi.versionName ?: "")
            o.put("c", versionCode(pi))
            o.put("f", huellaFirma(pi))

            val concedidos = JSONArray()
            val declarados = JSONArray()
            val pedidos = pi.requestedPermissions
            val banderas = pi.requestedPermissionsFlags
            if (pedidos != null) {
                for (i in pedidos.indices) {
                    val corto = pedidos[i].substringAfterLast('.')
                    if (PERMISOS_SENSIBLES.contains(corto)) {
                        val concedido = banderas != null && i < banderas.size &&
                            (banderas[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                        if (concedido) concedidos.put(corto)
                    } else if (PERMISOS_ESPECIALES.contains(corto)) {
                        declarados.put(corto)
                    }
                }
            }
            o.put("g", concedidos)
            o.put("d", declarados)
        } catch (e: Exception) {
        }
        return o
    }

    @Suppress("DEPRECATION")
    private fun versionCode(pi: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= 28) {
            pi.longVersionCode
        } else {
            pi.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun huellaFirma(pi: PackageInfo): String {
        return try {
            val firmas = if (Build.VERSION.SDK_INT >= 28) {
                pi.signingInfo?.apkContentsSigners
            } else {
                pi.signatures
            }
            if (firmas != null && firmas.isNotEmpty()) hex16(sha256(firmas[0].toByteArray())) else ""
        } catch (e: Exception) {
            ""
        }
    }

    @Suppress("DEPRECATION")
    private fun instalador(pm: PackageManager, paquete: String): String {
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

    private fun listaAjuste(clave: String): JSONArray {
        val lista = JSONArray()
        try {
            val texto = Settings.Secure.getString(ctx.contentResolver, clave)
            if (texto != null) {
                for (parte in texto.split(':')) {
                    if (parte.isNotBlank()) lista.put(parte)
                }
            }
        } catch (e: Exception) {
        }
        return lista
    }

    private fun ajusteGlobal(clave: String): Int {
        return try {
            Settings.Global.getInt(ctx.contentResolver, clave, 0)
        } catch (e: Exception) {
            0
        }
    }

    private fun administradores(): JSONArray {
        val lista = JSONArray()
        try {
            val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
            val admins = dpm.activeAdmins
            if (admins != null) {
                for (a in admins) lista.put(a.flattenToString())
            }
        } catch (e: Exception) {
        }
        return lista
    }

    private fun certificadosDeUsuario(): JSONArray {
        val lista = JSONArray()
        try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null, null)
            val alias = ks.aliases()
            while (alias.hasMoreElements()) {
                val a = alias.nextElement()
                if (a.startsWith("user:")) {
                    val cert = ks.getCertificate(a)
                    if (cert != null) lista.put(hex16(sha256(cert.encoded)))
                }
            }
        } catch (e: Exception) {
        }
        return lista
    }

    private fun sha256(datos: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(datos)

    private fun hex16(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in 0 until 8) {
            sb.append(String.format("%02x", bytes[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    companion object {
        const val INTERVALO_MS = 15L * 60L * 1000L

        /** Permisos de ejecución con riesgo alto: se anotan solo si están CONCEDIDOS. */
        val PERMISOS_SENSIBLES = setOf(
            "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CALL_LOG", "RECORD_AUDIO", "CAMERA",
            "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "READ_CONTACTS", "READ_PHONE_STATE",
            "READ_CALENDAR", "ANSWER_PHONE_CALLS", "PROCESS_OUTGOING_CALLS"
        )

        /** Permisos especiales: se anotan si la app los DECLARA (Android no dice si están activos). */
        val PERMISOS_ESPECIALES = setOf(
            "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE",
            "PACKAGE_USAGE_STATS", "WRITE_SETTINGS"
        )
    }
}
