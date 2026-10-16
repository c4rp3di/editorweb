package com.imagen.chat.ia.files

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class CreatedFilesManager(private val context: Context) {
    private val root = File(context.filesDir, "generadas").apply { mkdirs() }
    private val images = File(root, "images").apply { mkdirs() }
    private val other = File(root, "other").apply { mkdirs() }
    private val index = File(root, "index.json")

    fun directory(kind: CreatedFile.Kind): File = when (kind) {
        CreatedFile.Kind.IMAGE -> images
        CreatedFile.Kind.OTHER -> other
    }

    fun register(
        source: File,
        mimeType: String,
        kind: CreatedFile.Kind,
        conversationId: String? = null,
        prompt: String? = null,
        negativePrompt: String? = null,
        model: String? = null,
        seed: Long? = null,
        steps: Int? = null,
        cfg: Float? = null,
        width: Int? = null,
        height: Int? = null
    ): CreatedFile {
        require(source.isFile) { "El archivo generado no existe: ${source.absolutePath}" }
        val target = File(directory(kind), source.name)
        if (source.absolutePath != target.absolutePath) source.copyTo(target, overwrite = true)
        val item = CreatedFile(
            UUID.randomUUID().toString(), target.absolutePath, mimeType, kind,
            System.currentTimeMillis(), target.length(),
            conversationId, prompt, negativePrompt, model, seed, steps, cfg, width, height
        )
        save((read() + item).distinctBy { it.path }.sortedByDescending { it.createdAt })
        return item
    }

    fun read(): List<CreatedFile> {
        if (!index.isFile) return emptyList()
        return try {
            val arr = JSONArray(index.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val path = o.optString("path")
                    if (path.isBlank() || !File(path).isFile) continue
                    add(
                        CreatedFile(
                            o.optString("id"), path, o.optString("mimeType", "image/png"),
                            runCatching { CreatedFile.Kind.valueOf(o.optString("kind")) }.getOrDefault(CreatedFile.Kind.OTHER),
                            o.optLong("createdAt", File(path).lastModified()),
                            o.optLong("sizeBytes", File(path).length()),
                            o.optString("conversationId").ifBlank { null },
                            o.optString("prompt").ifBlank { null },
                            o.optString("negativePrompt").ifBlank { null },
                            o.optString("model").ifBlank { null },
                            o.optLongOrNull("seed"), o.optIntOrNull("steps"), o.optFloatOrNull("cfg"),
                            o.optIntOrNull("width"), o.optIntOrNull("height")
                        )
                    )
                }
            }.sortedByDescending { it.createdAt }
        } catch (_: Exception) { emptyList() }
    }

    fun delete(item: CreatedFile): Boolean {
        val deleted = File(item.path).delete()
        save(read().filterNot { it.id == item.id || it.path == item.path })
        return deleted
    }

    private fun save(items: List<CreatedFile>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id); put("path", item.path); put("mimeType", item.mimeType); put("kind", item.kind.name)
                put("createdAt", item.createdAt); put("sizeBytes", item.sizeBytes)
                item.conversationId?.let { put("conversationId", it) }
                item.prompt?.let { put("prompt", it) }
                item.negativePrompt?.let { put("negativePrompt", it) }
                item.model?.let { put("model", it) }
                item.seed?.let { put("seed", it) }
                item.steps?.let { put("steps", it) }
                item.cfg?.let { put("cfg", it) }
                item.width?.let { put("width", it) }
                item.height?.let { put("height", it) }
            })
        }
        index.writeText(arr.toString())
    }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.optFloatOrNull(key: String): Float? = if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
}
