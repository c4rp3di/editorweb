package com.carpe.panoptes.modules.jadx

import android.content.Context
import com.carpe.panoptes.modules.spi.AnalyzerModule
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.plugins.input.dex.DexInputPlugin
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
 *
 * v1.0.19: el build real falló con "Unresolved reference: plugins" en la
 * línea que registraba el plugin de entrada DEX a mano. La causa no era la
 * API de jadx — era una colisión de nombres: la variable local se llamaba
 * `jadx`, igual que el paquete raíz de jadx-core (`jadx.plugins...`), así
 * que `jadx.plugins.input.dex.DexInputPlugin()` resolvía `jadx` como la
 * variable (que no tiene ningún miembro `.plugins`) en vez de como el
 * paquete. Arreglado con un import explícito de DexInputPlugin y
 * renombrando la variable — así no hay forma de que vuelva a chocar.
 *
 * Lo que SÍ sigue sin poder verificarse aquí (sin red ni forma de compilar
 * localmente): que `registerPlugin(JadxPlugin)` sea el nombre y la firma
 * reales en jadx-core 1.4.7, y que `DexInputPlugin` sea de verdad pública
 * en ese paquete. Si el próximo build da un "Unresolved reference"
 * DISTINTO (p.ej. sobre registerPlugin o sobre DexInputPlugin en sí), es
 * la API la que hay que ajustar, no otra colisión de nombres — pégame ese
 * log y lo miro.
 */
class JadxAnalyzer : AnalyzerModule {

    private var decompilador: JadxDecompiler? = null

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

            val jd = JadxDecompiler(args)
            decompilador = jd
            // En Android el DEX del módulo no conserva META-INF/services (d8 solo
            // combina .class, no recursos), así que el ServiceLoader de jadx no
            // encuentra su propio plugin de entrada DEX: se registra a mano.
            jd.registerPlugin(DexInputPlugin())
            jd.load()
            listener.onProgress("decompilando", 40)

            jd.save()
            listener.onProgress("decompilando", 100)
            listener.onLog("jadx: guardado en $outputDir")

            val archivos = outputDir.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(outputDir).path }
                .toList()

            AnalyzerModule.ModuleResult(
                success = true,
                jsonOutput = "{\"classesDecompiled\":${jd.classes.size},\"filesWritten\":${archivos.size}}",
                outputFiles = archivos
            )
        } catch (e: Throwable) {
            AnalyzerModule.ModuleResult(
                success = false,
                jsonOutput = "{}",
                outputFiles = emptyList(),
                error = e.javaClass.simpleName + ": " + (e.message ?: "sin detalle") + " | " +
                    e.stackTrace.take(6).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            )
        }
    }

    override fun shutdown() {
        decompilador?.close()
        decompilador = null
    }
}

/** La versión real se inyecta en CI (misma que la del release de jadx empaquetado). */
internal object BuildConfigModule {
    const val VERSION = "1.4.7"
}
