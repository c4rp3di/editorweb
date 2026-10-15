package com.carpe.panoptes.modules

import org.json.JSONArray
import org.json.JSONObject

/** §5.2 del plan: catálogo de módulos descargables, firmado y versionado. */
object ModuleCatalog {

    data class Entry(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val sizeBytes: Long,
        val sha256: String,
        val signatureSha256: String,
        val url: String,
        val entryClass: String,
        val provides: List<String>,
        val requires: List<String>,
        val requiresRoot: Boolean,
        val requiresShim: Boolean
    ) {
        /** Antes de publicar vía CI, el catálogo lleva este valor de relleno. */
        val publicado: Boolean get() = sha256 != "PENDIENTE_DE_PUBLICAR" && sha256.isNotBlank()
    }

    data class Catalog(
        val schema: Int,
        val shellMinVersion: Int,
        val modules: List<Entry>
    )

    fun parse(json: String): Catalog {
        val o = JSONObject(json)
        val arr: JSONArray = o.optJSONArray("modules") ?: JSONArray()
        val entradas = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            Entry(
                id = m.getString("id"),
                name = m.optString("name", m.getString("id")),
                description = m.optString("description", ""),
                version = m.getString("version"),
                sizeBytes = m.optLong("size_bytes", 0),
                sha256 = m.optString("sha256", ""),
                signatureSha256 = m.optString("signature_sha256", ""),
                url = m.getString("url"),
                entryClass = m.getString("entry_class"),
                provides = m.optJSONArray("provides")?.let { p -> (0 until p.length()).map { p.getString(it) } } ?: emptyList(),
                requires = m.optJSONArray("requires")?.let { r -> (0 until r.length()).map { r.getString(it) } } ?: emptyList(),
                requiresRoot = m.optBoolean("requires_root", false),
                requiresShim = m.optBoolean("requires_shim", false)
            )
        }
        return Catalog(
            schema = o.optInt("schema", 1),
            shellMinVersion = o.optInt("shell_min_version", 1),
            modules = entradas
        )
    }
}
