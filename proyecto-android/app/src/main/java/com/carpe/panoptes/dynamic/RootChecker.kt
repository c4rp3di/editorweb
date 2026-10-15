package com.carpe.panoptes.dynamic

import java.io.File

/**
 * Comprobación de root deliberadamente simple (no pretende detectar/evadir
 * ocultadores de root — eso sería un objetivo distinto y no es lo que F6
 * necesita). Solo decide si tiene sentido ofrecer el módulo frida-server
 * en la UI (§10 del plan: "Usuario sin root no puede usar Frida -> Banner
 * explicativo").
 */
object RootChecker {

    private val RUTAS_SU = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/su", "/vendor/bin/su"
    )

    fun pareceRooteado(): Boolean {
        if (RUTAS_SU.any { File(it).exists() }) return true
        return try {
            val proceso = ProcessBuilder("which", "su").redirectErrorStream(true).start()
            proceso.waitFor()
            proceso.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /** Ejecuta un comando como root vía `su -c`, esperando a que termine. Devuelve el código de salida. */
    fun ejecutarComoRoot(comando: String): Int {
        return try {
            val proceso = ProcessBuilder("su", "-c", comando).redirectErrorStream(true).start()
            proceso.waitFor()
        } catch (e: Exception) {
            -1
        }
    }
}
