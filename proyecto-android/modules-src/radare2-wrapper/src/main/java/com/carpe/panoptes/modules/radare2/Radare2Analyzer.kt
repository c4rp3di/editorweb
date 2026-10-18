package com.carpe.panoptes.modules.radare2

import android.content.Context
import com.carpe.panoptes.modules.spi.AnalyzerModule
import com.carpe.panoptes.nativebridge.ExecShim
import java.io.File
import java.util.zip.ZipFile

/**
 * Wrapper SPI sobre radare2 (https://github.com/radareorg/radare2), el
 * único módulo de F4 que no es una librería JVM: es un binario nativo que
 * se descarga ya compilado (arm64-v8a) y se ejecuta vía ExecShim
 * (libexec-shim.so, ver §4 del plan) porque Android 10+ bloquea ejecutar
 * directamente un binario escrito a disco por la app (W^X).
 *
 * module.json de este módulo debe declarar "requiresShim": true (el
 * catálogo ya lo hace, §5.2). ModuleManager no hace nada especial con esa
 * bandera todavía más allá de informar al usuario en la UI — es el propio
 * módulo quien depende de que el shim exista (siempre existe: es parte
 * del shell, no algo que se instale aparte).
 */
class Radare2Analyzer : AnalyzerModule {

    private lateinit var r2Binary: File

    override fun id(): String = "radare2"
    override fun version(): String = "5.8.8"

    override fun initialize(context: Context, workDir: File): Boolean {
        // El binario r2 viene DENTRO del propio zip del módulo (bin/r2),
        // ya extraído junto a classes.dex por ModuleDownloader — workDir
        // aquí es filesDir/modules/radare2/work/, el binario está un nivel
        // arriba, en filesDir/modules/radare2/bin/r2.
        val moduleDir = workDir.parentFile ?: return false
        r2Binary = File(moduleDir, "bin/r2")
        return r2Binary.exists()
    }

    override fun analyze(
        apkPath: String,
        outputDir: File,
        listener: AnalyzerModule.ProgressListener
    ): AnalyzerModule.ModuleResult {
        return try {
            if (!::r2Binary.isInitialized || !r2Binary.exists()) {
                return AnalyzerModule.ModuleResult(false, "{}", emptyList(), error = "r2 binary no disponible")
            }

            listener.onProgress("extrayendo .so", 0)
            val libsDir = File(outputDir, "libs").apply { mkdirs() }
            val sos = extraerLibsNativas(File(apkPath), libsDir)

            if (sos.isEmpty()) {
                return AnalyzerModule.ModuleResult(
                    success = true,
                    jsonOutput = "{\"nativeLibs\":0}",
                    outputFiles = emptyList()
                )
            }

            val resultados = mutableListOf<String>()
            sos.forEachIndexed { idx, so ->
                listener.onProgress("analizando ${so.name}", (idx * 100) / sos.size)
                val salidaJson = File(outputDir, "${so.name}.r2.json")
                // 'aaa' = análisis automático completo; 'iqj' = info del binario en JSON;
                // 'aflj' = lista de funciones en JSON. -q0 = modo no interactivo.
                val codigo = ExecShim.run(
                    binary = r2Binary,
                    args = listOf("-q0", "-c", "aaa; iqj; aflj", so.absolutePath),
                    workDir = outputDir,
                    stdoutFile = salidaJson
                )
                if (codigo == 0 && salidaJson.exists()) {
                    resultados.add(salidaJson.name)
                } else {
                    listener.onLog("radare2: fallo analizando ${so.name} (código $codigo)")
                }
            }

            listener.onProgress("analizando", 100)
            AnalyzerModule.ModuleResult(
                success = true,
                jsonOutput = "{\"nativeLibs\":${sos.size},\"analyzed\":${resultados.size}}",
                outputFiles = resultados
            )
        } catch (e: Exception) {
            AnalyzerModule.ModuleResult(false, "{}", emptyList(), error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Extrae las .so de arm64-v8a (o cualquier ABI presente) del APK a un directorio plano. */
    private fun extraerLibsNativas(apk: File, destino: File): List<File> {
        val extraidos = mutableListOf<File>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .forEach { entry ->
                    val nombrePlano = entry.name.substringAfterLast('/')
                    val salida = File(destino, nombrePlano)
                    zip.getInputStream(entry).use { input ->
                        salida.outputStream().use { output -> input.copyTo(output) }
                    }
                    extraidos.add(salida)
                }
        }
        return extraidos
    }

    override fun shutdown() {
        // sin estado persistente entre llamadas
    }
}
