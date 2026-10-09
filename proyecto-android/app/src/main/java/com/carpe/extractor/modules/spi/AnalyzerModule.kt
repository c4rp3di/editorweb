package com.carpe.panoptes.modules.spi

import android.content.Context
import java.io.File

/**
 * Contrato que TODO módulo descargable de Panoptes debe implementar
 * (jadx, apktool, radare2, frida-server...).
 *
 * El shell conoce esta interfaz; los módulos, compilados por separado en
 * `modules-src/` y publicados como .zip firmados en GitHub Releases, la
 * implementan y se cargan en tiempo de ejecución vía DexClassLoader.
 *
 * CONTRATO ESTABLE: ver Apéndice B del plan. Los cambios se hacen siempre
 * por adición (nuevos métodos con valor por defecto), nunca por
 * modificación o eliminación — romper esto invalida todos los módulos ya
 * publicados. Versionado por SemVer; un cambio mayor se señaliza con
 * `shell_min_version` en el catálogo.
 *
 * F0: esta interfaz existe pero todavía no hay ningún módulo que la
 * implemente ni infraestructura de descarga — eso llega en F2.
 */
interface AnalyzerModule {

    /** Identificador único, coincide con el "id" del catálogo (p.ej. "jadx"). */
    fun id(): String

    /** Versión del módulo, coincide con el "version" del catálogo. */
    fun version(): String

    /**
     * Inicialización. Devuelve true si el módulo puede operar en este
     * dispositivo (puede comprobar root, espacio en disco, .so nativos...).
     */
    fun initialize(context: Context, workDir: File): Boolean

    /** Ejecuta el análisis sobre un APK. El resultado debe ser serializable a JSON. */
    fun analyze(apkPath: String, outputDir: File, listener: ProgressListener): ModuleResult

    /** Libera recursos (JNI, file handles, procesos hijos, etc.). */
    fun shutdown()

    interface ProgressListener {
        fun onProgress(phase: String, percent: Int)
        fun onLog(line: String)
    }

    data class ModuleResult(
        val success: Boolean,
        val jsonOutput: String,
        val outputFiles: List<String>,
        val error: String? = null
    )

    // Añadidos posteriores, siempre con valor por defecto — nunca romper el contrato:
    // fun capabilities(): List<String> = emptyList()
    // fun requiredPermissions(): List<String> = emptyList()
}
