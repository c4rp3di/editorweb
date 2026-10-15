package com.carpe.panoptes.modules

/**
 * ⚠️ RELLENA ESTO con tu repo real antes de compilar F2.
 * Todo lo demás (catálogo, descargas, lista blanca de hosts) depende de
 * estos dos valores.
 */
object Config {
    const val GITHUB_OWNER = "TU_USUARIO_O_ORG"
    const val GITHUB_REPO = "panoptes"

    /** Release donde publish-catalog.yml sube el catalog.json regenerado. */
    const val CATALOG_RELEASE_TAG = "catalog-latest"
    const val CATALOG_ASSET_NAME = "module_catalog.json"

    val CATALOG_URL: String
        get() = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$CATALOG_RELEASE_TAG/$CATALOG_ASSET_NAME"
}
