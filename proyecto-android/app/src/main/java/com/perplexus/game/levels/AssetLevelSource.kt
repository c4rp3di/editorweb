package com.perplexus.game.levels

import android.content.Context
import com.perplexus.game.diag.GameLog
import java.io.File

/**
 * Niveles incluidos en assets/levels (índice index.json) más los que el jugador copie a
 * Android/data/<paquete>/files/levels/*.json (se añaden sin recompilar, RF-17).
 */
class AssetLevelSource(private val context: Context) : LevelSource {
    private fun externalDir(): File? = try { context.getExternalFilesDir("levels") } catch (_: Exception) { null }

    override fun listLevelFiles(): List<String> {
        val out = ArrayList<String>()
        try {
            val idx = context.assets.open("levels/index.json").bufferedReader().use { it.readText() }
            for (f in LevelLoader.parseIndex(idx)) out.add("asset:$f")
        } catch (e: Exception) {
            GameLog.e("Levels", "No se pudo leer levels/index.json", e)
        }
        externalDir()?.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { out.add("ext:" + it.absolutePath) }
        return out
    }

    override fun readLevel(file: String): String =
        if (file.startsWith("asset:")) context.assets.open("levels/" + file.removePrefix("asset:")).bufferedReader().use { it.readText() }
        else File(file.removePrefix("ext:")).readText()
}
