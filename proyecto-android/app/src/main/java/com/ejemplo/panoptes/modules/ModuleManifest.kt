package com.carpe.panoptes.modules

import org.json.JSONObject

/** module.json que acompaña a cada módulo ya instalado en filesDir/modules/<id>/. */
data class ModuleManifest(
    val id: String,
    val version: String,
    val entryClass: String,
    val dexName: String
) {
    companion object {
        // Formatos estrictos (auditoría H-04): el id acaba siendo nombre de directorio y
        // dexName acaba en File(dir, dexName), así que no pueden llevar separadores de ruta,
        // ".." ni rutas absolutas.
        private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        private val VERSION_REGEX = Regex("^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$")
        private val DEX_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\\.dex$")
        private val CLASS_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_.$]{0,199}$")

        fun esIdValido(id: String): Boolean = ID_REGEX.matches(id)
        fun esVersionValida(version: String): Boolean = VERSION_REGEX.matches(version)
        fun esClaseValida(clase: String): Boolean = CLASS_REGEX.matches(clase)

        /** Devuelve null si el JSON no es válido o algún campo no cumple el formato. */
        fun parse(json: String): ModuleManifest? = try {
            val o = JSONObject(json)
            val manifest = ModuleManifest(
                id = o.getString("id"),
                version = o.getString("version"),
                entryClass = o.getString("entryClass"),
                dexName = o.optString("dexName", "classes.dex")
            )
            if (esIdValido(manifest.id) &&
                esVersionValida(manifest.version) &&
                esClaseValida(manifest.entryClass) &&
                DEX_REGEX.matches(manifest.dexName)
            ) manifest else null
        } catch (e: Exception) {
            null
        }
    }
}
