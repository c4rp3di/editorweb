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
     * publish-catalog.yml cada vez que sale un módulo nuevo), pero SOLO lo
     * acepta si:
     *   · Config apunta a un repo real (no el marcador de plantilla),
     *   · hay una clave pública fijada en Config.CATALOG_PUBLIC_KEY_B64,
     *   · la firma publicada junto al catálogo es válida, y
     *   · el catálogo pasa la validación de esquema de ModuleCatalog.parse.
     * Si algo de eso falla (sin red, sin configurar, firma inválida, esquema
     * inválido...) se descarta el remoto por completo y se usa el catálogo
     * embebido en assets/module_catalog.json, que viaja dentro del APK.
     */
    suspend fun load(): ModuleCatalog.Catalog = withContext(Dispatchers.IO) {
        if (Config.repoConfigurado && Config.firmaConfigurada) {
            try {
                val cuerpo = descargar(Config.CATALOG_URL)
                val firma = descargar(Config.CATALOG_SIGNATURE_URL)
                if (cuerpo != null && firma != null &&
                    CatalogSignature.verify(cuerpo, String(firma, Charsets.UTF_8), Config.CATALOG_PUBLIC_KEY_B64)
                ) {
                    return@withContext ModuleCatalog.parse(String(cuerpo, Charsets.UTF_8))
                }
            } catch (e: Exception) {
                // sin red, release inexistente, catálogo con esquema inválido...: caemos al embebido
            }
        }
        loadEmbedded()
    }

    /** Descarga con tope de tamaño; null si la respuesta no es 2xx o es demasiado grande. */
    private fun descargar(url: String): ByteArray? {
        val req = Request.Builder().url(url).build()
        return client.newCall(req).execute().use { res ->
            val cuerpo = res.body
            if (!res.isSuccessful || cuerpo == null || cuerpo.contentLength() > MAX_CATALOGO_BYTES) {
                null
            } else {
                cuerpo.byteStream().use { input -> leerAcotado(input) }
            }
        }
    }

    private fun leerAcotado(input: java.io.InputStream): ByteArray? {
        val salida = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var n: Int
        while (input.read(buf).also { n = it } > 0) {
            salida.write(buf, 0, n)
            if (salida.size() > MAX_CATALOGO_BYTES) return null
        }
        return salida.toByteArray()
    }

    private fun loadEmbedded(): ModuleCatalog.Catalog {
        val json = context.assets.open("module_catalog.json").bufferedReader().use { it.readText() }
        return ModuleCatalog.parse(json)
    }

    private companion object {
        const val MAX_CATALOGO_BYTES = 1024 * 1024
    }
}
