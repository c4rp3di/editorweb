package com.lab.netlens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

object Perms {
    val SENSITIVE: Map<String, String> = linkedMapOf(
        "android.permission.ACCESS_FINE_LOCATION" to "Ubicación precisa",
        "android.permission.ACCESS_COARSE_LOCATION" to "Ubicación aproximada",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "Ubicación en segundo plano",
        "android.permission.CAMERA" to "Cámara",
        "android.permission.RECORD_AUDIO" to "Micrófono",
        "android.permission.READ_CONTACTS" to "Leer contactos",
        "android.permission.WRITE_CONTACTS" to "Modificar contactos",
        "android.permission.GET_ACCOUNTS" to "Cuentas del dispositivo",
        "android.permission.READ_SMS" to "Leer SMS",
        "android.permission.RECEIVE_SMS" to "Recibir SMS",
        "android.permission.SEND_SMS" to "Enviar SMS",
        "android.permission.READ_CALL_LOG" to "Registro de llamadas",
        "android.permission.READ_PHONE_STATE" to "Estado del teléfono",
        "android.permission.READ_PHONE_NUMBERS" to "Número de teléfono",
        "android.permission.CALL_PHONE" to "Llamar",
        "android.permission.READ_CALENDAR" to "Leer calendario",
        "android.permission.READ_EXTERNAL_STORAGE" to "Leer almacenamiento",
        "android.permission.WRITE_EXTERNAL_STORAGE" to "Escribir almacenamiento",
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "Acceso total a archivos",
        "android.permission.READ_MEDIA_IMAGES" to "Fotos",
        "android.permission.READ_MEDIA_VIDEO" to "Vídeos",
        "android.permission.READ_MEDIA_AUDIO" to "Audio",
        "android.permission.BLUETOOTH_SCAN" to "Buscar dispositivos Bluetooth",
        "android.permission.BLUETOOTH_CONNECT" to "Conectar Bluetooth",
        "android.permission.NEARBY_WIFI_DEVICES" to "Dispositivos Wi-Fi cercanos",
        "android.permission.BODY_SENSORS" to "Sensores corporales",
        "android.permission.ACTIVITY_RECOGNITION" to "Actividad física",
        "android.permission.POST_NOTIFICATIONS" to "Notificaciones",
        "android.permission.SYSTEM_ALERT_WINDOW" to "Dibujar sobre otras apps",
        "android.permission.QUERY_ALL_PACKAGES" to "Ver todas las apps instaladas",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "Instalar apps"
    )

    class Info(val pkg: String, val label: String, val hasInternet: Boolean, val perms: List<Pair<String, Boolean>>) {
        val granted: Int get() = perms.count { it.second }
    }

    fun scan(pm: PackageManager, self: String): List<Info> {
        val res = ArrayList<Info>()
        val all: List<PackageInfo> = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            else @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) { emptyList() }
        for (pi in all) {
            val ai = pi.applicationInfo ?: continue
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (system || pi.packageName == self) continue
            val req = pi.requestedPermissions ?: emptyArray()
            val fl = pi.requestedPermissionsFlags
            var internet = false
            val list = ArrayList<Pair<String, Boolean>>()
            for (i in req.indices) {
                val name = req[i]
                if (name == "android.permission.INTERNET") internet = true
                val human = SENSITIVE[name] ?: continue
                val g = fl != null && i < fl.size && (fl[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                list.add(Pair(human, g))
            }
            val label = try { ai.loadLabel(pm).toString() } catch (e: Exception) { pi.packageName }
            res.add(Info(pi.packageName, label, internet, list))
        }
        res.sortWith(compareByDescending<Info> { it.granted }.thenBy { it.label.lowercase() })
        return res
    }

    fun forPackage(pm: PackageManager, pkg: String): Info? = scan(pm, "").firstOrNull { it.pkg == pkg }
}

object Insights {
    fun fmt(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1048576 -> String.format("%.1f KB", n / 1024.0)
        else -> String.format("%.2f MB", n / 1048576.0)
    }

    fun forApp(s: AppStat, p: Perms.Info?): String {
        val sb = StringBuilder()
        sb.append("Aplicación: ").append(s.label).append("  (").append(s.pkg).append(")\n\n")
        sb.append("Tráfico observado: ↑ ").append(fmt(s.up.get())).append("  ↓ ").append(fmt(s.down.get())).append("  · ").append(s.flows.get()).append(" conexiones\n")
        sb.append("Dominios contactados: ").append(s.hosts.size).append("\n")
        if (s.orgs.isNotEmpty()) sb.append("Propietarios/ASN: ").append(s.orgs.take(5).joinToString(", ")).append("\n")
        val top = s.hosts.take(12)
        if (top.isNotEmpty()) sb.append("   ").append(top.joinToString(", ")).append(if (s.hosts.size > 12) " …" else "").append("\n")
        val ads = s.trackers.filter { it.startsWith("Anuncios") }.map { it.substringAfter('|') }
        val ana = s.trackers.filter { it.startsWith("Analítica") }.map { it.substringAfter('|') }
        val cr = s.trackers.filter { it.startsWith("Informes") }.map { it.substringAfter('|') }
        if (ads.isNotEmpty()) sb.append("\n🎯 Anuncios: ").append(ads.joinToString(", "))
        if (ana.isNotEmpty()) sb.append("\n🎯 Analítica: ").append(ana.joinToString(", "))
        if (cr.isNotEmpty()) sb.append("\n🎯 Informes de fallos: ").append(cr.joinToString(", "))
        if (s.clearFlows.get() > 0) {
            sb.append("\n\n⚠ ").append(s.clearFlows.get()).append(" conexiones HTTP SIN CIFRAR (cualquiera en la red puede leerlas).")
            if (s.sensitive.isNotEmpty()) sb.append("\n⚠ Claves sensibles vistas en claro: ").append(s.sensitive.joinToString(", "))
        }
        if (s.mitmCount.get() > 0) {
            sb.append("\n\n🔓 ").append(s.mitmCount.get()).append(" conexiones TLS fueron descifradas con la CA local (MITM). Solo visible si la app aceptó el certificado.")
        }
        if (p != null) {
            val g = p.perms.filter { it.second }.map { it.first }
            sb.append("\n\nPermisos sensibles CONCEDIDOS: ")
            sb.append(if (g.isEmpty()) "ninguno" else g.joinToString(", "))
            if (!p.hasInternet) sb.append("\n(No declara el permiso INTERNET: por sí sola no puede conectarse.)")
            val net = s.trackers.isNotEmpty()
            val loc = g.any { it.startsWith("Ubicación") }
            val sens = g.any { it == "Cámara" || it == "Micrófono" || it.contains("contactos") || it.contains("SMS") || it == "Registro de llamadas" }
            if (loc && net) sb.append("\n\n📍 Alcance: puede leer tu ubicación y además contacta con dominios de anuncios/analítica. Con MITM activo se puede ver qué envía exactamente (solo si la app acepta la CA).")
            else if (sens && net) sb.append("\n\n📍 Alcance: tiene permisos delicados y contacta con terceros de analítica/anuncios. Conviene revisar qué envía realmente.")
        }
        sb.append("\n\nQué NO vemos sin MITM: el contenido de las conexiones TLS.")
        return sb.toString()
    }
}

