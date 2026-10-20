package com.carpe.panoptes.nativebridge

import java.io.File

/**
 * ⚠️ STUB DE SOLO COMPILACIÓN. NO es la implementación real.
 *
 * Existe únicamente para que Radare2Analyzer.kt compile en este proyecto
 * Gradle independiente. La clase real vive en el shell
 * (app/src/main/java/.../nativebridge/ExecShim.kt) con la carga JNI de
 * verdad. En tiempo de ejecución, el DexClassLoader del módulo delega
 * primero al classloader padre (el de la app) para cualquier clase — así
 * que `com.carpe.panoptes.nativebridge.ExecShim` se resuelve a la clase
 * REAL del shell, no a este stub, SIEMPRE QUE este stub nunca se incluya
 * en el classes.dex final del módulo.
 *
 * build-module-radare2.yml debe excluir explícitamente este .class al
 * combinar los jars con d8 (ver el paso "Empaquetar" del workflow) —
 * si algún día cambias el pipeline de build, no olvides ese detalle o el
 * módulo dejará de encontrar la implementación real.
 */
object ExecShim {
    fun run(binary: File, args: List<String> = emptyList(), workDir: File? = null, stdoutFile: File? = null): Int {
        throw IllegalStateException(
            "Stub de compilación invocado en runtime — build-module-radare2.yml " +
                "no excluyó este .class del classes.dex del módulo. Revisa el workflow."
        )
    }
}
