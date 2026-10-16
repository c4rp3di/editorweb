package com.ejemplo.chat.ia.llama

/**
 * Modelo GGUF destinado al runtime nativo de llama.cpp.
 *
 * Esta capa describe y verifica el modelo, pero no lo conecta al chat hasta que
 * la biblioteca nativa llama.cpp esté realmente incluida en el APK.
 */
data class LlamaCppModel(
    val id: String,
    val nombre: String,
    val url: String,
    val archivo: String,
    val sha256: String,
    val tamanoAproximadoMb: Int,
    val arquitectura: String,
    val cuantizacion: String
) {
    companion object {
        /**
         * DeepSeek-R1-Distill-Qwen-1.5B Q4_K_M de QuantFactory.
         * SHA-256 verificado en la fuente del modelo consultada para esta fase.
         */
        val DEEPSEEK_R1_DISTILL_QWEN_1_5B_Q4_K_M = LlamaCppModel(
            id = "deepseek-r1-distill-qwen-1.5b-q4_k_m",
            nombre = "DeepSeek-R1-Distill-Qwen-1.5B Q4_K_M",
            url = "https://huggingface.co/QuantFactory/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B.Q4_K_M.gguf",
            archivo = "DeepSeek-R1-Distill-Qwen-1.5B.Q4_K_M.gguf",
            sha256 = "41aa31689f2cbdcc5172e370db2ab7a10e17a9427520602437bd16d8d127d105",
            tamanoAproximadoMb = 1120,
            arquitectura = "Qwen2",
            cuantizacion = "Q4_K_M"
        )
    }
}
