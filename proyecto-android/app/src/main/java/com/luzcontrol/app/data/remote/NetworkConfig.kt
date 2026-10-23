package com.luzcontrol.app.data.remote

/**
 * Certificate pinning (OkHttp). Añade aquí los hashes SPKI SHA-256 en formato "sha256/XXXX=" de cada dominio.
 * Si la lista de un dominio está vacía NO se fija (así una rotación de certificado no deja la app inutilizable).
 * Recomendado en producción: fijar al menos 2 pines (el certificado actual y uno de respaldo).
 *
 * Cómo obtener un pin:
 *   openssl s_client -connect datadis.es:443 -servername datadis.es < /dev/null 2>/dev/null \
 *     | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \
 *     | openssl dgst -sha256 -binary | openssl enc -base64
 */
object NetworkConfig {
    const val DATADIS_HOST = "datadis.es"
    const val ESIOS_HOST = "api.esios.ree.es"
    const val REE_HOST = "apidatos.ree.es"

    val PINS: Map<String, List<String>> = mapOf(
        DATADIS_HOST to emptyList(),
        ESIOS_HOST to emptyList(),
        REE_HOST to emptyList()
    )

    const val DATADIS_BASE = "https://datadis.es/"
    const val ESIOS_BASE = "https://api.esios.ree.es/"
    const val REE_BASE = "https://apidatos.ree.es/"
}
