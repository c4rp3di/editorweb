package com.carpe.panoptes.modules

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class ModuleCatalogRepository(private val context: Context) {

    private val client = OkHttpClient()

    /**
     * Intenta el catálogo publicado en GitHub Releases (lo regenera
     * publish-catalog.yml cada vez que sale un módulo nuevo). Si falla
     * (sin red, repo sin configurar en Config.kt, release inexistente aún...)
     * cae al catálogo embebido en assets/module_catalog.json, que en F0/F1
     * está vacío y se va llenando a mano mientras no haya CI.
     */
    suspend fun load(): ModuleCatalog.Catalog = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(Config.CATALOG_URL).build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    if (!body.isNullOrBlank()) return@withContext ModuleCatalog.parse(body)
                }
            }
        } catch (e: Exception) {
            // sin red, repo no configurado todavía, o catalog-latest no existe aún: caemos al embebido
        }
        loadEmbedded()
    }

    private fun loadEmbedded(): ModuleCatalog.Catalog {
        val json = context.assets.open("module_catalog.json").bufferedReader().use { it.readText() }
        return ModuleCatalog.parse(json)
    }
}
