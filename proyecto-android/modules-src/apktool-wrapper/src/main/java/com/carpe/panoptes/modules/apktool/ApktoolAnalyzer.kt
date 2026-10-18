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

    override fun initialize(context: Context, workDir: File): Boolean = true

    override fun analyze(
        apkPath: String,
        outputDir: File,
        listener: AnalyzerModule.ProgressListener
    ): AnalyzerModule.ModuleResult {
        return try {
            listener.onProgress("decodificando", 0)
            listener.onLog("apktool: decodificando $apkPath")

            val config = AndrolibConfig.getDefaultConfig().apply {
                // Solo recursos + manifest, no smali: el parsing de código ya
                // lo cubre dexlib2 (shell, F1) y jadx (módulo, F2) — pedir
                // también smali aquí sería trabajo duplicado sin beneficio.
                decodeSources = AndrolibConfig.DECODE_SOURCES_NONE
            }

            val decoder = ApkDecoder(config, ExtFile(File(apkPath)))
            if (outputDir.exists()) outputDir.deleteRecursively()
            decoder.decode(outputDir)

            listener.onProgress("decodificando", 100)

            val archivos = outputDir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(outputDir).path }
                .toList()

            AnalyzerModule.ModuleResult(
                success = true,
                jsonOutput = "{\"filesWritten\":${archivos.size}}",
                outputFiles = archivos
            )
        } catch (e: Exception) {
            AnalyzerModule.ModuleResult(
                success = false,
                jsonOutput = "{}",
                outputFiles = emptyList(),
                error = e.message ?: e.javaClass.simpleName
            )
        }
    }

    override fun shutdown() {
        // apktool-lib no mantiene handles abiertos entre llamadas
    }
}
