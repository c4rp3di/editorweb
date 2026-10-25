package com.carpe.panoptes.analyzer.dexlib

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import java.io.File
import java.util.zip.ZipFile

/**
 * Parseo DEX rápido en el shell, sin módulos (§6.2 del plan).
 * No decompila (eso es jadx, F2) — solo enumera clases/métodos/campos
 * para dar una visión estructural inmediata del APK.
 *
 * Límites (auditoría H-09): nº de DEX por APK y tamaño de cada uno. Lo que
 * supere un límite se OMITE y queda anotado en [omitidos], para que el
 * orquestador lo reporte como análisis parcial en vez de agotar memoria o disco.
 */
class DexParser {

    data class DexSummary(
        val dexName: String,
        val classCount: Int,
        val methodCount: Int,
        val fieldCount: Int,
        val packages: Set<String>,
        /** DEX ya extraído a disco, para que otros pasos (strings) lo lean sin volver a extraerlo. */
        val file: File
    )

    /** Motivos de cada DEX omitido por superar un límite (se acumulan entre APKs). */
    val omitidos = mutableListOf<String>()

    /**
     * Extrae todos los classesN.dex del APK y los parsea uno a uno.
     * [etiqueta] separa los DEX de cada APK de un conjunto split ("base", "split1"...):
     * todos los APK tienen un classes.dex y no deben pisarse.
     */
    fun parseApk(apkFile: File, workDir: File, etiqueta: String = "base"): List<DexSummary> {
        val dexDir = File(workDir, "dex/$etiqueta").apply { mkdirs() }
        val resumenes = mutableListOf<DexSummary>()
        val nombreDex = Regex("classes\\d*\\.dex")

        ZipFile(apkFile).use { zip ->
            val entradas = zip.entries().asSequence().filter { nombreDex.matches(it.name) }.toList()
            for (entry in entradas) {
                if (resumenes.size >= MAX_DEX_POR_APK) {
                    omitidos.add("$etiqueta/${entry.name}: se superó el máximo de $MAX_DEX_POR_APK DEX por APK")
                    continue
                }
                if (entry.size > MAX_DEX_BYTES) {
                    omitidos.add("$etiqueta/${entry.name}: ${entry.size / 1048576} MB supera el límite de ${MAX_DEX_BYTES / 1048576} MB")
                    continue
                }
                val destino = File(dexDir, entry.name)
                // entry.size puede mentir (o ser -1): el límite también se aplica a los bytes realmente copiados.
                val completo = zip.getInputStream(entry).use { input ->
                    destino.outputStream().use { output -> copiarAcotado(input, output, MAX_DEX_BYTES) }
                }
                if (!completo) {
                    destino.delete()
                    omitidos.add("$etiqueta/${entry.name}: supera el límite de ${MAX_DEX_BYTES / 1048576} MB al descomprimir")
                    continue
                }
                resumenes.add(parseDexFile(destino, "$etiqueta/${entry.name}"))
            }
        }
        return resumenes
    }

    /** Copia como mucho [maximo] bytes; false si el origen tenía más. */
    private fun copiarAcotado(input: java.io.InputStream, output: java.io.OutputStream, maximo: Long): Boolean {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        var n: Int
        while (input.read(buf).also { n = it } > 0) {
            total += n
            if (total > maximo) return false
            output.write(buf, 0, n)
        }
        return true
    }

    private fun parseDexFile(dexFile: File, nombre: String): DexSummary {
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
            dexName = nombre,
            classCount = dex.classes.count(),
            methodCount = metodos,
            fieldCount = campos,
            packages = paquetes,
            file = dexFile
        )
    }

    private companion object {
        const val MAX_DEX_POR_APK = 50
        const val MAX_DEX_BYTES = 100L * 1024 * 1024
    }
}
