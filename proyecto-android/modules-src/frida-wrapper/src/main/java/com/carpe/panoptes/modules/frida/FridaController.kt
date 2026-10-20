package com.carpe.panoptes.modules.frida

import android.content.Context
import com.carpe.panoptes.modules.spi.AnalyzerModule
import com.carpe.panoptes.nativebridge.ExecShim
import com.carpe.panoptes.python.PythonBridge
import org.json.JSONObject
import java.io.File

/**
 * Módulo de instrumentación dinámica (F6, §9 del plan: "frida-server como
 * módulo, scripts de unpinning"). El único módulo que requiere root.
 *
 * Arquitectura honesta (ver aviso grande en python/frida_runner.py del
 * shell): esto SIEMPRE arranca frida-server correctamente en el
 * dispositivo — esa parte no depende de nada incierto, es solo copiar un
 * binario y ejecutarlo como root vía ExecShim. Lo que SÍ es incierto es
 * si Panoptes puede controlarlo desde el propio dispositivo (requiere el
 * paquete Python `frida`, con extensión nativa, vía Chaquopy). Si falla,
 * `analyze()` lo refleja en el resultado en vez de fingir éxito — y
 * frida-server se queda arrancado y disponible para controlarlo desde un
 * PC conectado al dispositivo, que es como se usa Frida normalmente.
 */
class FridaController : AnalyzerModule {

    private lateinit var moduleDir: File
    private var pythonBridge: PythonBridge? = null

    override fun id(): String = "frida-server"
    override fun version(): String = "16.1.4"

    override fun initialize(context: Context, workDir: File): Boolean {
        moduleDir = workDir.parentFile ?: return false
        val binario = File(moduleDir, "bin/frida-server")
        if (!binario.exists()) return false

        pythonBridge = try {
            PythonBridge(context)
        } catch (e: Exception) {
            null // el shell no tiene Chaquopy disponible por el motivo que sea; seguimos sin control on-device
        }
        return true
    }

    /**
     * apkPath aquí se usa solo para derivar el nombre de paquete a
     * instrumentar (se asume ya instalado — es el mismo que se analizó
     * estáticamente). outputDir recibe scripts.json con los eventos
     * capturados, si el control on-device funcionó.
     */
    override fun analyze(
        apkPath: String,
        outputDir: File,
        listener: AnalyzerModule.ProgressListener
    ): AnalyzerModule.ModuleResult {
        val binario = File(moduleDir, "bin/frida-server")
        if (!binario.exists()) {
            return AnalyzerModule.ModuleResult(false, "{}", emptyList(), error = "frida-server no disponible")
        }

        listener.onProgress("arrancando frida-server", 0)
        val codigoArranque = ExecShim.run(
            binary = binario,
            args = listOf("-l", "127.0.0.1:27042", "-D"), // -D: daemonize
            workDir = moduleDir
        )
        if (codigoArranque != 0) {
            return AnalyzerModule.ModuleResult(
                success = false, jsonOutput = "{}", outputFiles = emptyList(),
                error = "frida-server no arrancó (código $codigoArranque) — ¿tiene la app acceso root?"
            )
        }
        listener.onLog("frida-server escuchando en 127.0.0.1:27042")

        val bridge = pythonBridge
        if (bridge == null) {
            // frida-server sigue disponible aunque no podamos controlarlo
            // on-device: éxito parcial, el usuario puede conectarse desde un PC.
            return AnalyzerModule.ModuleResult(
                success = true,
                jsonOutput = "{\"serverStarted\":true,\"onDeviceControl\":false}",
                outputFiles = emptyList()
            )
        }

        listener.onProgress("instrumentando", 30)
        val targetPackage = inferirPaqueteDesdeApkPath(apkPath)
            ?: return fallo("No se pudo deducir un nombre de paquete válido de $apkPath")
        val scriptPath = File(moduleDir, "scripts/ssl-unpinning-and-log.js")
        if (!scriptPath.exists()) {
            // Auditoría H-05: un script ausente es un fallo de la instrumentación, no un éxito.
            return fallo("script no empaquetado en el módulo (frida-server sí está arrancado)")
        }

        val resultadoJson = try {
            bridge.runFrida(targetPackage, scriptPath.absolutePath, timeoutSeconds = 15)
        } catch (e: Exception) {
            return fallo("la instrumentación lanzó una excepción: ${e.message}")
        }

        listener.onProgress("instrumentando", 100)

        // La respuesta es JSON de frida_runner.py; si trae "error" (sin frida, timeout,
        // fallo al instrumentar...) el resultado es un FALLO explícito, aunque haya eventos parciales.
        val respuesta = try {
            JSONObject(resultadoJson)
        } catch (e: Exception) {
            return fallo("respuesta de Frida no válida (no es JSON)")
        }
        val archivoSalida = File(outputDir, "frida-events.json")
        archivoSalida.writeText(resultadoJson)
        if (respuesta.has("error")) {
            return AnalyzerModule.ModuleResult(
                success = false,
                jsonOutput = resultadoJson,
                outputFiles = listOf(archivoSalida.name),
                error = respuesta.optString("error", "error de Frida")
            )
        }

        return AnalyzerModule.ModuleResult(
            success = true,
            jsonOutput = resultadoJson,
            outputFiles = listOf(archivoSalida.name)
        )
    }

    /** Resultado fallido con el JSON construido por JSONObject (el mensaje se escapa solo). */
    private fun fallo(motivo: String): AnalyzerModule.ModuleResult {
        val json = JSONObject()
        json.put("serverStarted", true)
        json.put("onDeviceControl", false)
        json.put("error", motivo)
        return AnalyzerModule.ModuleResult(
            success = false,
            jsonOutput = json.toString(),
            outputFiles = emptyList(),
            error = motivo
        )
    }

    private fun inferirPaqueteDesdeApkPath(apkPath: String): String? {
        // El nombre de archivo lo genera ApkExtractor como
        // "<packageName>-<version>-<original>.apk" (ver ingest/ApkExtractor.kt
        // del shell) — frágil pero evita tener que pasar el packageName por
        // todo el pipeline solo para este módulo. Mejora pendiente: que
        // AnalyzerModule.analyze() reciba el packageName explícitamente en
        // una versión futura del SPI (ver Apéndice B: solo por adición).
        // Un nombre de paquete Android no puede contener '-', así que cortar en el primer
        // guion es fiable mientras se respete esa convención; si no, se valida y se falla
        // explícitamente en vez de instrumentar un paquete equivocado (auditoría H-13).
        val candidato = File(apkPath).name.substringBefore('-')
        return if (PAQUETE_VALIDO.matches(candidato)) candidato else null
    }

    private companion object {
        val PAQUETE_VALIDO = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }

    override fun shutdown() {
        // Nota: no matamos frida-server aquí a propósito — si el control
        // on-device falló, queremos que siga disponible para conectar desde
        // un PC sin tener que reinstalar/reabrir la app.
    }
}
