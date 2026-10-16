package com.carpe.panoptes.modules

/**
 * ⚠️ RELLENA ESTO con tu repo real antes de compilar F2.
 * Todo lo demás (catálogo, descargas, lista blanca de hosts) depende de
 * estos valores.
 */
object Config {
    const val GITHUB_OWNER = "TU_USUARIO_O_ORG"
    const val GITHUB_REPO = "panoptes"

    /** Release donde publish-catalog.yml sube el catalog.json regenerado. */
    const val CATALOG_RELEASE_TAG = "catalog-latest"
    const val CATALOG_ASSET_NAME = "module_catalog.json"

    /** Firma del catálogo (auditoría H-02): se publica junto al catálogo, ver scripts/sign-catalog.py. */
    const val CATALOG_SIGNATURE_ASSET_NAME = "module_catalog.json.sig"

    /**
     * Clave PÚBLICA ECDSA P-256 (X.509 / SubjectPublicKeyInfo, DER en Base64) con la que se
     * verifica la firma del catálogo. Se genera con `python3 scripts/sign-catalog.py genkey`.
     * Mientras esté vacía, el catálogo remoto NO se considera de confianza y se usa solo el
     * embebido. La clave PRIVADA nunca debe entrar en el repositorio ni en la app.
     */
    const val CATALOG_PUBLIC_KEY_B64 = ""

    /** H-03: detecta el marcador de plantilla sin personalizar. */
    val repoConfigurado: Boolean
        get() = GITHUB_OWNER.isNotBlank() && GITHUB_OWNER != "TU_USUARIO_O_ORG"

    val firmaConfigurada: Boolean
        get() = CATALOG_PUBLIC_KEY_B64.isNotBlank()

    private val releaseBase: String
        get() = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$CATALOG_RELEASE_TAG"

    val CATALOG_URL: String
        get() = "$releaseBase/$CATALOG_ASSET_NAME"

    val CATALOG_SIGNATURE_URL: String
        get() = "$releaseBase/$CATALOG_SIGNATURE_ASSET_NAME"
}
