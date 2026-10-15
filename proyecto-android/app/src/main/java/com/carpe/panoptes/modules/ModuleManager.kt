package com.carpe.panoptes.modules

import android.content.Context
import com.carpe.panoptes.modules.spi.AnalyzerModule
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestiona los módulos instalados en filesDir/modules/<id>/.
 *
 * F0: solo sabe escanear qué hay instalado (nada, al principio) y cargar un
 * módulo si alguna vez aparece uno ahí a mano. El `ModuleDownloader` que
 * trae módulos desde GitHub Releases con verificación SHA-256 + firma
 * llega en F2 — no se incluye aquí para no dar una falsa sensación de que
 * ya se pueden descargar jadx/apktool/radare2/frida.
 */
class ModuleManager(private val context: Context) {

    private val modulesDir = File(context.filesDir, "modules").apply { mkdirs() }
    private val installed = ConcurrentHashMap<String, InstalledModule>()
    private val loaded = ConcurrentHashMap<String, AnalyzerModule>()

    data class InstalledModule(
        val id: String,
        val version: String,
        val dir: File,
        val entryClass: String,
        val dexFile: File,
        val nativeDir: File?
    )

    fun scanInstalled() {
        installed.clear()
        modulesDir.listFiles()?.forEach { dir ->
            val manifestFile = File(dir, "module.json")
            if (!manifestFile.exists()) return@forEach
            val info = ModuleManifest.parse(manifestFile.readText()) ?: return@forEach
            installed[info.id] = InstalledModule(
                id = info.id,
                version = info.version,
                dir = dir,
                entryClass = info.entryClass,
                dexFile = File(dir, info.dexName),
                nativeDir = File(dir, "lib").takeIf { it.exists() }
            )
        }
    }

    fun listInstalled(): List<InstalledModule> = installed.values.toList()

    fun isInstalled(id: String): Boolean = installed.containsKey(id)

    /** Carga el módulo (DexClassLoader + SPI). Cachea la instancia. */
    fun load(id: String): AnalyzerModule {
        loaded[id]?.let { return it }
        val inst = installed[id] ?: error("Módulo $id no instalado")

        val parent = javaClass.classLoader!!
        val loader = dalvik.system.DexClassLoader(
            inst.dexFile.absolutePath,
            inst.nativeDir?.absolutePath,
            null,
            parent
        )

        val clazz = loader.loadClass(inst.entryClass)
        require(AnalyzerModule::class.java.isAssignableFrom(clazz)) {
            "La clase ${inst.entryClass} no implementa AnalyzerModule"
        }
        val instance = clazz.getDeclaredConstructor().newInstance() as AnalyzerModule
        require(instance.id() == id) { "Mismatch de id: esperado $id, obtenido ${instance.id()}" }

        val workDir = File(inst.dir, "work").apply { mkdirs() }
        check(instance.initialize(context, workDir)) { "El módulo $id falló al inicializarse" }

        loaded[id] = instance
        return instance
    }

    fun unload(id: String) {
        loaded.remove(id)?.shutdown()
    }

    fun unloadAll() {
        loaded.keys.toList().forEach { unload(it) }
    }

    fun uninstall(id: String) {
        unload(id)
        installed.remove(id)?.dir?.deleteRecursively()
    }
}
