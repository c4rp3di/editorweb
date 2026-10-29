package com.carpe.panoptes.nativebridge

import java.io.File

/**
 * Wrapper Kotlin sobre libexec-shim.so (§4 del plan). Permite ejecutar
 * binarios descargados como módulos (radare2) sorteando la restricción
 * W^X de Android 10+, siempre que el binario ya se haya dejado en modo
 * solo-lectura+ejecutable (el propio shim lo fuerza con chmod antes de
 * invocarlo).
 *
 * IMPORTANTE para quien escriba un módulo que necesite esto (radare2,
 * p.ej.): esta clase vive en el proceso del shell, no en el módulo
 * descargado. Un módulo cargado vía DexClassLoader comparte la jerarquía
 * de classloaders con la app (DexClassLoader delega primero al padre), así
 * que referenciar `com.carpe.panoptes.nativebridge.ExecShim` desde un
 * módulo resuelve a ESTA clase real, ya cargada — no hace falta duplicar
 * la implementación nativa en cada módulo. Ver modules-src/radare2-wrapper
 * para el patrón exacto (ahí el "ExecShim.kt" que ven en tiempo de
 * compilación es solo un stub compileOnly, nunca se empaqueta en el DEX
 * del módulo).
 */
object ExecShim {

    private var cargado = false

    @Synchronized
    private fun asegurarCargado() {
        if (!cargado) {
            System.loadLibrary("exec-shim")
            cargado = true
        }
    }

    /**
     * @param stdoutFile si se da, el stdout del proceso se redirige ahí
     *   (el shim no captura stdout en memoria — más simple y evita límites
     *   de tamaño de pipe para salidas grandes como JSON de radare2).
     */
    fun run(binary: File, args: List<String> = emptyList(), workDir: File? = null, stdoutFile: File? = null): Int {
        require(binary.exists()) { "Binario no encontrado: $binary" }
        asegurarCargado()
        return nativeRun(binary.absolutePath, args.toTypedArray(), workDir?.absolutePath, stdoutFile?.absolutePath)
    }

    private external fun nativeRun(path: String, args: Array<String>, workDir: String?, stdoutFile: String?): Int
}
