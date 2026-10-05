package com.ejemplo.chat.ia.files

data class CreatedFile(
    val id: String,
    val path: String,
    val mimeType: String,
    val kind: Kind,
    val createdAt: Long,
    val sizeBytes: Long,
    val conversationId: String? = null,
    val prompt: String? = null,
    val model: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val frameCount: Int? = null
) {
    enum class Kind { IMAGE, VIDEO, OTHER }
}
