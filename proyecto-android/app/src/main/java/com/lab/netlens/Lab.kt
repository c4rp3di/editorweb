package com.lab.netlens

object Lab {

    data class LabResult(val evidence: String, val advice: String)

    fun evidenceLevel(sensitivePerms: Int, trackers: Int, clearHttp: Int, mitmCount: Int): LabResult {
        return when {
            mitmCount > 0 -> LabResult("CONFIRMADO", "Se observó contenido descifrado mediante MITM en una app que acepta la CA local.")
            clearHttp > 0 -> LabResult("EVIDENCIA", "Hay tráfico HTTP sin cifrar. Revisa qué datos se envían en claro.")
            sensitivePerms > 0 && trackers > 0 -> LabResult("POSIBLE RIESGO", "La app tiene permisos sensibles y contacta con terceros. No es prueba de fuga.")
            else -> LabResult("SIN SEÑALES", "No se detectaron patrones preocupantes en esta sesión.")
        }
    }

    fun pinningLikely(failures: Int): Boolean = failures >= 3

    fun safeNetworkConfig(): String = """
        <network-security-config>
            <debug-overrides>
                <trust-anchors>
                    <certificates src="user" />
                </trust-anchors>
            </debug-overrides>
        </network-security-config>
    """.trimIndent()

    fun releaseChecklist(): List<String> = listOf(
        "Desactivar confianza en certificados de usuario en release.",
        "No incluir la CA de NetLens en producción.",
        "Revisar permisos sensibles no usados.",
        "Eliminar HTTP sin cifrar.",
        "Verificar que el pinning sigue activo.",
        "Probar la app en un perfil aislado antes de publicar."
    )
}

