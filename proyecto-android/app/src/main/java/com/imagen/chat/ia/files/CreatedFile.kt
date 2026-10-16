package com.imagen.chat.ia.files

data class CreatedFile(
    val id: String,
    val path: String,
    val mimeType: String,
    val kind: Kind,
    val createdAt: Long,
    val sizeBytes: Long,
    val conversationId: String? = null,
    val prompt: String? = null,
    val negativePrompt: String? = null,
    val model: String? = null,
    val seed: Long? = null,
    val steps: Int? = null,
    val cfg: Float? = null,
    val width: Int? = null,
    val height: Int? = null
) {
    enum class Kind { IMAGE, OTHER }
}
