package com.carpe.panoptes.modules

/**
 * Datos del repositorio de módulos que el usuario introduce en la propia app
 * (Ajustes → Catálogo de módulos). No hay nada que rellenar en el código.
 * `clavePublicaB64` es la clave PÚBLICA ECDSA P-256 (X.509 DER en Base64) que genera
 * `python3 scripts/sign-catalog.py genkey`. La clave privada nunca entra en la app.
 */
data class CatalogoConfig(
    val owner: String = "",
    val repo: String = Config.DEFAULT_REPO,
    val clavePublicaB64: String = ""
) {
    val repoConfigurado: Boolean
        get() = owner.isNotBlank() && Config.esOwnerValido(owner) && Config.esRepoValido(repo)

    val firmaConfigurada: Boolean
        get() = clavePublicaB64.isNotBlank()

    private val releaseBase: String
        get() = "https://github.com/$owner/$repo/releases/download/${Config.CATALOG_RELEASE_TAG}"

    val catalogUrl: String get() = "$releaseBase/${Config.CATALOG_ASSET_NAME}"
    val signatureUrl: String get() = "$releaseBase/${Config.CATALOG_SIGNATURE_ASSET_NAME}"
}

object Config {
    const val DEFAULT_REPO = "panoptes"

    /** Release donde publish-catalog.yml sube el catálogo regenerado. */
    const val CATALOG_RELEASE_TAG = "catalog-latest"
    const val CATALOG_ASSET_NAME = "module_catalog.json"
    const val CATALOG_SIGNATURE_ASSET_NAME = "module_catalog.json.sig"

    private val OWNER_RE = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
    private val REPO_RE = Regex("^[A-Za-z0-9._-]{1,100}$")

    fun esOwnerValido(s: String) = OWNER_RE.matches(s)
    fun esRepoValido(s: String) = REPO_RE.matches(s) && s != "." && s != ".."
}
