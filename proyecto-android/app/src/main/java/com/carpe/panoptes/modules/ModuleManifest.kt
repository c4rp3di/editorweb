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
        fun parse(json: String): ModuleManifest? = try {
            val o = JSONObject(json)
            ModuleManifest(
                id = o.getString("id"),
                version = o.getString("version"),
                entryClass = o.getString("entryClass"),
                dexName = o.optString("dexName", "classes.dex")
            )
        } catch (e: Exception) {
            null
        }
    }
}
