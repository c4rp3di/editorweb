package com.carpe.panoptes.modules.apktool

import android.content.Context
import com.carpe.panoptes.modules.spi.AnalyzerModule
import brut.androlib.ApkDecoder
import brut.androlib.Config as AndrolibConfig
import brut.directory.ExtFile
import java.io.File

/**
 * Wrapper SPI sobre apktool-lib (https://github.com/iBotPeaches/Apktool).
 * Decodifica recursos y AndroidManifest.xml a texto legible — el "Manifest
 * básico" de F1 (ManifestParser.kt, vía PackageManager) ya cubre permisos
 * y componentes exportados sin este módulo; apktool añade el manifest
 * completo tal cual, los layouts/recursos y los smali si se pide, para
 * quien necesite ir más allá de lo que la API pública de Android expone.
 *
 * Nota de compatibilidad: escrito contra la superficie pública de
 * apktool-lib (clase ApkDecoder) tal y como se documenta en el proyecto.
 * Si el CI la compila contra una versión cuya API haya cambiado, este es
 * el único archivo a tocar.
 */
class ApktoolAnalyzer : AnalyzerModule {

    override fun id(): String = "apktool"
    override fun version(): String = "2.9.3"

    private var directorioFrameworkPropio: File? = null

    override fun initialize(context: Context, workDir: File): Boolean {
        // v1.0.21 — hipótesis para el ExceptionInInitializerError en
        // Config.setDefaultFrameworkDirectory: apktool-lib calcula su
        // directorio de framework por defecto a partir de la propiedad de
        // sistema "user.home" (convención de escritorio: ~/.local/share/
        // apktool/framework o similar). Android no tiene un "home" de usuario
        // en ese sentido — "user.home" puede no apuntar a ningún sitio
        // escribible por la app, y el fallo ocurre en el inicializador
        // estático de Config, antes de que podamos interceptarlo con un
        // try/catch normal. Redirigirlo a un directorio propio y garantizado
        // escribible, ANTES de que nada de apktool-lib se toque, es barato y
        // no debería poder empeorar nada si la hipótesis fuera incorrecta.
        directorioFrameworkPropio = File(workDir, "apktool-framework").apply { mkdirs() }
        val anterior = System.getProperty("user.home")
        if (anterior == null || !File(anterior).canWrite()) {
            System.setProperty("user.home", workDir.absolutePath)
        }
        return true
    }

    override fun analyze(
        apkPath: String,
        outputDir: File,
        listener: AnalyzerModule.ProgressListener
    ): AnalyzerModule.ModuleResult {
        return try {
            listener.onProgress("decodificando", 0)
            listener.onLog("apktool: decodificando $apkPath")

            val config = AndrolibConfig.getDefaultConfig().apply {
                directorioFrameworkPropio?.let { frameworkDirectory = it.absolutePath }
                // Solo recursos + manifest, no smali: el parsing de código ya
                // lo cubre dexlib2 (shell, F1) y jadx (módulo, F2) — pedir
                // también smali aquí sería trabajo duplicado sin beneficio.
                decodeSources = AndrolibConfig.DECODE_SOURCES_NONE
            }

            var nota = ""
            try {
                ApkDecoder(config, ExtFile(File(apkPath))).decode(outputDir)
            } catch (e: Throwable) {
                // Sin el framework de Android embebido (el DEX del módulo pierde los recursos del jar)
                // la decodificación completa falla: se reintenta copiando los recursos sin decodificar.
                nota = "decodificación completa no disponible (${describirError(e)}); recursos sin decodificar"
                listener.onLog("apktool: $nota")
                if (outputDir.exists()) outputDir.deleteRecursively()
                outputDir.mkdirs()
                config.decodeResources = AndrolibConfig.DECODE_RESOURCES_NONE
                ApkDecoder(config, ExtFile(File(apkPath))).decode(outputDir)
            }

            listener.onProgress("decodificando", 100)

            val archivos = outputDir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(outputDir).path }
                .toList()

            AnalyzerModule.ModuleResult(
                success = true,
                jsonOutput = "{\"filesWritten\":${archivos.size},\"nota\":\"${nota.replace("\"", "'")}\"}",
                outputFiles = archivos
            )
        } catch (e: Throwable) {
            AnalyzerModule.ModuleResult(
                success = false,
                jsonOutput = "{}",
                outputFiles = emptyList(),
                error = describirError(e)
            )
        }
    }

    override fun shutdown() {
        // apktool-lib no mantiene handles abiertos entre llamadas
    }

    /**
     * v1.0.21: ExceptionInInitializerError (fallo de inicializador estático,
     * justo lo que dio "sin detalle" en el informe real) tiene casi siempre
     * e.message == null — la causa de verdad vive en e.cause. Recorre toda
     * la cadena en vez de solo el nivel más externo.
     */
    private fun describirError(e: Throwable): String {
        val cadena = generateSequence(e) { it.cause }.take(5).toList()
        val resumen = cadena.joinToString(" <- causado por <- ") {
            "${it.javaClass.simpleName}: ${it.message ?: "(sin mensaje)"}"
        }
        val rastro = e.stackTrace.take(6)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        return "$resumen | $rastro"
    }
}
