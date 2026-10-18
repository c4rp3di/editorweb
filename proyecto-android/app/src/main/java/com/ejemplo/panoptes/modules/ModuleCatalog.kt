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

    /** Versión de esquema que este shell sabe interpretar. */
    const val SCHEMA_SOPORTADO = 1

    private const val PENDIENTE = "PENDIENTE_DE_PUBLICAR"
    private const val MAX_TAMANO_MODULO = 200L * 1024 * 1024
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Parsea Y VALIDA el catálogo (auditoría H-14). Si algo no cumple, lanza
     * IllegalArgumentException y el catálogo entero se rechaza: no se
     * degrada a datos a medias.
     */
    fun parse(json: String): Catalog {
        val o = JSONObject(json)
        val schema = o.optInt("schema", 1)
        require(schema == SCHEMA_SOPORTADO) { "Esquema de catálogo no soportado: $schema" }
        val shellMin = o.optInt("shell_min_version", 1)
        require(shellMin >= 1) { "shell_min_version no válido: $shellMin" }

        val arr: JSONArray = o.optJSONArray("modules") ?: JSONArray()
        val entradas = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val entry = Entry(
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
            validarEntrada(entry)
            entry
        }
        val ids = entradas.map { it.id }
        require(ids.size == ids.toSet().size) { "El catálogo tiene ids de módulo duplicados" }
        return Catalog(
            schema = schema,
            shellMinVersion = shellMin,
            modules = entradas
        )
    }

    private fun validarEntrada(e: Entry) {
        require(ModuleManifest.esIdValido(e.id)) { "Id de módulo no válido: ${e.id}" }
        require(ModuleManifest.esVersionValida(e.version)) { "Versión no válida en ${e.id}: ${e.version}" }
        require(ModuleManifest.esClaseValida(e.entryClass)) { "entry_class no válido en ${e.id}" }
        require(e.sizeBytes in 0..MAX_TAMANO_MODULO) { "size_bytes fuera de rango en ${e.id}" }
        // sha256 / signature_sha256: hash hexadecimal, o el relleno "PENDIENTE_DE_PUBLICAR" / vacío
        // mientras el módulo no está publicado (ver Entry.publicado).
        require(e.sha256 == PENDIENTE || e.sha256.isEmpty() || SHA256_REGEX.matches(e.sha256)) { "sha256 no válido en ${e.id}" }
        require(e.signatureSha256 == PENDIENTE || e.signatureSha256.isEmpty() || SHA256_REGEX.matches(e.signatureSha256)) {
            "signature_sha256 no válido en ${e.id}"
        }
        if (e.publicado) {
            require(SHA256_REGEX.matches(e.signatureSha256)) { "Falta signature_sha256 en ${e.id}" }
        }
        val url = try { java.net.URL(e.url) } catch (ex: Exception) { null }
        require(url != null && url.protocol == "https" && url.host in ModuleDownloader.ALLOWED_HOSTS) {
            "URL no permitida en ${e.id}: ${e.url}"
        }
    }
}
