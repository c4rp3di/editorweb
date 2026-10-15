package com.carpe.panoptes.analyzer.dexlib

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import java.io.File
import java.util.zip.ZipFile

/**
 * Parseo DEX rápido en el shell, sin módulos (§6.2 del plan).
 * No decompila (eso es jadx, F2) — solo enumera clases/métodos/campos
 * para dar una visión estructural inmediata del APK.
 */
class DexParser {

    data class DexSummary(
        val dexName: String,
        val classCount: Int,
        val methodCount: Int,
        val fieldCount: Int,
        val packages: Set<String>
    )

    /** Extrae todos los classesN.dex del APK y los parsea uno a uno. */
    fun parseApk(apkFile: File, workDir: File): List<DexSummary> {
        val dexDir = File(workDir, "dex").apply { mkdirs() }
        val resumenes = mutableListOf<DexSummary>()

        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .forEach { entry ->
                    val destino = File(dexDir, entry.name)
                    zip.getInputStream(entry).use { input ->
                        destino.outputStream().use { output -> input.copyTo(output) }
                    }
                    resumenes.add(parseDexFile(destino))
                }
        }
        return resumenes
    }

    private fun parseDexFile(dexFile: File): DexSummary {
        val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        var metodos = 0
        var campos = 0
        val paquetes = mutableSetOf<String>()

        for (claseDef in dex.classes) {
            metodos += claseDef.methods.count()
            campos += claseDef.fields.count()
            // Descriptor tipo "Lcom/ejemplo/Foo;" -> paquete "com.ejemplo"
            val descriptor = claseDef.type
            val fin = descriptor.lastIndexOf('/')
            if (descriptor.startsWith("L") && fin > 1) {
                paquetes.add(descriptor.substring(1, fin).replace('/', '.'))
            }
        }

        return DexSummary(
            dexName = dexFile.name,
            classCount = dex.classes.count(),
            methodCount = metodos,
            fieldCount = campos,
            packages = paquetes
        )
    }
}
