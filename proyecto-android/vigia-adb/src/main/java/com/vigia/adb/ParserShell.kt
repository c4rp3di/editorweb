package com.vigia.adb

/** Un proceso de `ps`. [uid] es el UID de Android (10000+ para apps de usuario), o -1 si el nombre de usuario no se reconoce. */
class ProcesoInfo(val pid: Int, val usuario: String, val uid: Int, val rssKb: Long, val nombre: String) {
    /** Paquete al que pertenece (el nombre del proceso sin «:subproceso»), o null si no parece una app. */
    val paquete: String? get() = if (uid >= 10000 && nombre.contains('.')) nombre.substringBefore(':') else null
}

/** Una operación de AppOps (cámara, micrófono, ubicación…) con su modo y cuánto hace que se usó. */
class OperacionApp(val operacion: String, val modo: String, val haceMs: Long)

/** Lectores de la salida de comandos de shell. Son funciones puras para poder probarlas con texto de ejemplo. */
object ParserShell {

    /** `ps -A -o PID,USER,RSS,NAME` (toybox). */
    fun procesos(salida: String): List<ProcesoInfo> {
        val res = ArrayList<ProcesoInfo>()
        for (linea in salida.lines()) {
            val partes = linea.trim().split(Regex("\\s+"), 4)
            if (partes.size < 4) continue
            val pid = partes[0].toIntOrNull() ?: continue
            val rss = partes[2].toLongOrNull() ?: continue
            res.add(ProcesoInfo(pid, partes[1], uidDeUsuario(partes[1]), rss, partes[3].trim()))
        }
        return res
    }

    /** u0_a123 → 10123; u10_a45 → 1010045; root → 0; system → 1000; shell → 2000. */
    fun uidDeUsuario(usuario: String): Int {
        when (usuario) {
            "root" -> return 0
            "system" -> return 1000
            "shell" -> return 2000
        }
        val m = Regex("^u(\\d+)_a(\\d+)$").find(usuario) ?: return -1
        val perfil = m.groupValues[1].toInt()
        val app = m.groupValues[2].toInt()
        return perfil * 100000 + 10000 + app
    }

    /** `dumpsys battery`: pares «clave: valor». */
    fun bateria(salida: String): Map<String, String> {
        val res = LinkedHashMap<String, String>()
        for (linea in salida.lines()) {
            val i = linea.indexOf(':')
            if (i <= 0) continue
            val clave = linea.substring(0, i).trim()
            val valor = linea.substring(i + 1).trim()
            if (clave.isEmpty() || valor.isEmpty()) continue
            res[clave] = valor
        }
        return res
    }

    /** Permisos de ejecución de `dumpsys package <paquete>`: nombre → concedido. */
    fun permisosEjecucion(salida: String): Map<String, Boolean> {
        val res = LinkedHashMap<String, Boolean>()
        var dentro = false
        for (linea in salida.lines()) {
            val t = linea.trim()
            if (t.startsWith("runtime permissions:")) {
                dentro = true
                continue
            }
            if (dentro) {
                val m = Regex("^([A-Za-z0-9_.]+): granted=(true|false)").find(t)
                if (m != null) {
                    res[m.groupValues[1]] = m.groupValues[2] == "true"
                } else if (t.isNotEmpty() && !linea.startsWith("      ") && !linea.startsWith("\t\t")) {
                    // Otra sección: se acabó la lista.
                    if (!t.contains("granted=")) dentro = false
                }
            }
        }
        return res
    }

    /** `cmd appops get <paquete>`: «CAMERA: allow; time=+1d2h3m4s5ms ago». */
    fun appOps(salida: String): List<OperacionApp> {
        val res = ArrayList<OperacionApp>()
        for (linea in salida.lines()) {
            val m = Regex("^\\s*([A-Z_0-9]+):\\s*([a-z_]+)(.*)$").find(linea) ?: continue
            val resto = m.groupValues[3]
            val t = Regex("time=\\+?([0-9a-z]+) ago").find(resto)
            val hace = if (t != null) duracionMs(t.groupValues[1]) else -1L
            res.add(OperacionApp(m.groupValues[1], m.groupValues[2], hace))
        }
        return res
    }

    /** «1d2h3m4s5ms» → milisegundos. */
    fun duracionMs(texto: String): Long {
        var total = 0L
        for (m in Regex("(\\d+)(ms|d|h|m|s)").findAll(texto)) {
            val n = m.groupValues[1].toLong()
            total += when (m.groupValues[2]) {
                "d" -> n * 86_400_000L
                "h" -> n * 3_600_000L
                "m" -> n * 60_000L
                "s" -> n * 1000L
                else -> n
            }
        }
        return total
    }
}
