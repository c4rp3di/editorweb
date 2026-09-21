package com.carpe.microlisto.data

/**
 * Representa una conversación completa.
 */
data class Conversacion(
    val id: Long = 0,
    val titulo: String,
    val fechaMs: Long,
    val duracionMs: Long,
    val numHablantes: Int,
    val rutaAudio: String,
    val transcripcion: String,
    val resumen: String = ""
)

/**
 * Representa un segmento de la transcripción atribuido a un hablante.
 */
data class Segmento(
    val id: Long = 0,
    val idConversacion: Long,
    val hablanteId: Int,
    val inicioMs: Long,
    val finMs: Long,
    val texto: String,
    val confianza: Float = 1.0f
)