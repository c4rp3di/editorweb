package com.carpe.panoptes.python

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Puente hacia panoptes/androguard_runner.py y panoptes/quark_runner.py
 * (F5, §6.2 del plan: "[4] Python (Chaquopy)"). Chaquopy expone el
 * intérprete embebido vía com.chaquo.python.Python — no hace falta JNI a
 * mano aquí, eso ya lo resuelve el plugin.
 */
class PythonBridge(private val context: Context) {

    init {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    private val py get() = Python.getInstance()

    /** Devuelve el JSON crudo de androguard_runner.run(apkPath). */
    fun runAndroguard(apkPath: String): String {
        val modulo = py.getModule("panoptes.androguard_runner")
        return modulo.callAttr("run", apkPath).toString()
    }

    /**
     * Devuelve el JSON crudo de quark_runner.run(apkPath, rulesDir).
     * rulesDir puede ser null — quark_runner.py se degrada con gracia en
     * ese caso (ver el aviso grande en ese archivo sobre el estado real
     * de esta integración).
     */
    fun runQuark(apkPath: String): String {
        val modulo = py.getModule("panoptes.quark_runner")
        val rulesDir = prepararReglasQuarkSiExisten()
        return modulo.callAttr("run", apkPath, rulesDir?.absolutePath).toString()
    }

    /**
     * F6: intenta controlar frida-server on-device. Ver el aviso grande en
     * python/panoptes/frida_runner.py — esta llamada puede devolver
     * {"error": "..."} con normalidad si el wheel de 'frida' no resolvió
     * en la build; FridaController.kt sabe manejarlo.
     */
    fun runFrida(targetPackage: String, scriptPath: String, timeoutSeconds: Int): String {
        val modulo = py.getModule("panoptes.frida_runner")
        return modulo.callAttr("run", targetPackage, scriptPath, timeoutSeconds).toString()
    }

    /**
     * Si la app trae assets/quark-rules.zip (un subconjunto de reglas
     * empaquetado a mano — ver nota en quark_runner.py), lo descomprime a
     * filesDir la primera vez y reutiliza la copia en llamadas siguientes.
     * Si no existe ese asset, devuelve null sin error.
     */
    private fun prepararReglasQuarkSiExisten(): File? {
        val destino = File(context.filesDir, "quark-rules")
        if (destino.exists() && destino.listFiles()?.isNotEmpty() == true) return destino

        val assetName = "quark-rules.zip"
        val disponible = try {
            context.assets.open(assetName).use { true }
        } catch (e: Exception) {
            false
        }
        if (!disponible) return null

        return try {
            destino.mkdirs()
            context.assets.open(assetName).use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            File(destino, entry.name).outputStream().use { zis.copyTo(it) }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
            destino
        } catch (e: Exception) {
            null
        }
    }
}
