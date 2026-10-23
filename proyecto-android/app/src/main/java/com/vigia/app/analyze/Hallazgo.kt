package com.vigia.app.analyze

/** Resultado de una regla o motor de análisis, antes de guardarse en la base. */
class Hallazgo(
    val ts: Long,
    val severidad: String,
    val modulo: String,
    val regla: String,
    val app: String,
    val titulo: String,
    val descripcion: String,
    val evidencias: List<String>,
    val confianza: Int
)

object Severidad {
    const val INFO = "info"
    const val BAJA = "baja"
    const val MEDIA = "media"
    const val ALTA = "alta"
    const val CRITICA = "critica"

    fun peso(s: String): Int {
        return when (s) {
            CRITICA -> 16
            ALTA -> 8
            MEDIA -> 4
            BAJA -> 2
            else -> 1
        }
    }

    fun etiqueta(s: String): String {
        return when (s) {
            CRITICA -> "CRÍTICA"
            ALTA -> "ALTA"
            MEDIA -> "MEDIA"
            BAJA -> "BAJA"
            else -> "INFO"
        }
    }
}
