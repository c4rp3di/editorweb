package com.carpe.panoptes.nativebridge

import java.io.File

/** ⚠️ STUB DE SOLO COMPILACIÓN — ver el mismo aviso en radare2-wrapper. */
object ExecShim {
    fun run(binary: File, args: List<String> = emptyList(), workDir: File? = null, stdoutFile: File? = null): Int {
        throw IllegalStateException("Stub de compilación invocado en runtime — revisa la exclusión en build.gradle")
    }
}
