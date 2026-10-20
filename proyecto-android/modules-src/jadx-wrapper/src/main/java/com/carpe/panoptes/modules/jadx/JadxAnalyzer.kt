package com.carpe.panoptes.modules.jadx

import android.content.Context
import com.carpe.panoptes.modules.spi.AnalyzerModule
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import java.io.File

/**
 * Wrapper SPI sobre jadx-core (https://github.com/skylot/jadx).
 * Los jars de jadx-core se combinan con esta clase en un único classes.dex
 * en CI (.github/workflows/build-module-jadx.yml) — en tiempo de ejecución
 * ambos viven en el mismo DexClassLoader, así que las llamadas directas a
 * la API de jadx funcionan con normalidad.
 *
 * Nota de compatibilidad: esta clase se escribió contra la superficie
 * pública de jadx-api tal y como se documenta en el proyecto (JadxArgs /
 * JadxDecompiler / load() / save() / close()). Si el CI la compila contra
 * una versión de jadx cuya API haya cambiado, este es el único archivo que
 * hay que tocar.
 */
class JadxAnalyzer : AnalyzerModule {

    private var decompiler: JadxDecompiler? = null

    override fun id(): String = "jadx"
    override fun version(): String = BuildConfigModule.VERSION

    override fun initialize(context: Context, workDir: File): Boolean {
        // jadx no necesita inicialización previa (no es nativo, no necesita root).
        return true
    }

    override fun analyze(
        apkPath: String,
        outputDir: File,
        listener: AnalyzerModule.ProgressListener
    ): AnalyzerModule.ModuleResult {
        return try {
            listener.onProgress("decompilando", 0)
            listener.onLog("jadx: cargando $apkPath")

            val args = JadxArgs().apply {
                inputFiles = listOf(File(apkPath))
                outDir = outputDir
                threadsCount = 2
                isSkipResources = true   // los recursos los cubre el módulo apktool, no jadx
                isShowInconsistentCode = false
            }

            val jadx = JadxDecompiler(args)
            decompiler = jadx
            jadx.load()
            listener.onProgress("decompilando", 40)

            jadx.save()
            listener.onProgress("decompilando", 100)
            listener.onLog("jadx: guardado en $outputDir")

            val archivos = outputDir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(outputDir).path }
                .toList()

            AnalyzerModule.ModuleResult(
                success = true,
                jsonOutput = "{\"classesDecompiled\":${jadx.classes.size},\"filesWritten\":${archivos.size}}",
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
        decompiler?.close()
        decompiler = null
    }
}

/** La versión real se inyecta en CI (misma que la del release de jadx empaquetado). */
internal object BuildConfigModule {
    const val VERSION = "1.4.7"
}
