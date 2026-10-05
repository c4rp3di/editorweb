package com.ejemplo.chat.ia.flux

import java.io.File

object Flux2Files {
    const val MODEL_ID = "flux2-klein-4b"

    val graphs = listOf(
        "kc_prep.tflite", "kc_double0.tflite", "kc_double1.tflite",
        "kc_single0.tflite", "kc_single1.tflite", "kc_single2.tflite", "kc_single3.tflite", "kc_final.tflite",
        "ke_enc0.tflite", "ke_enc1.tflite", "ke_enc2.tflite", "kv_vae.tflite",
        "kce_prep.tflite", "kce_double0.tflite", "kce_double1.tflite", "kce_single0.tflite",
        "kce_single1.tflite", "kce_single2.tflite", "kce_single3.tflite", "kce_final.tflite",
        "kce2_prep.tflite", "kce2_double0.tflite", "kce2_double1.tflite", "kce2_single0.tflite",
        "kce2_single1.tflite", "kce2_single2.tflite", "kce2_single3.tflite", "kce2_final.tflite",
        "kv_vae_enc.tflite"
    )
    val tokenizerFiles = listOf(
        "tokenizer/qwen_embed_fp16.bin", "tokenizer/qwen_merges.txt", "tokenizer/qwen_special.txt",
        "tokenizer/qwen_vocab.txt", "tokenizer/tokenizer_fixture.txt"
    )
    val hostFiles = listOf("host/time_guidance_embed_bf16.bin")

    fun isComplete(root: File): Boolean = missing(root).isEmpty()

    fun missing(root: File): List<String> = (graphs + tokenizerFiles + hostFiles).filter { rel ->
        val f = File(root, rel)
        !f.isFile || f.length() < minimumBytes(rel)
    }

    fun minimumBytes(rel: String): Long = when {
        rel.startsWith("ke_enc") -> 850L * 1024 * 1024
        rel == "kc_prep.tflite" || rel == "kce_prep.tflite" || rel == "kce2_prep.tflite" -> 150L * 1024 * 1024
        rel.contains("double0") -> 650L * 1024 * 1024
        rel.contains("double1") -> 430L * 1024 * 1024
        rel.contains("single") -> 540L * 1024 * 1024
        rel.endsWith("final.tflite") -> 15L * 1024 * 1024
        rel.endsWith("kv_vae.tflite") -> 40L * 1024 * 1024
        rel.endsWith("kv_vae_enc.tflite") -> 30L * 1024 * 1024
        rel == "tokenizer/qwen_embed_fp16.bin" -> 700L * 1024 * 1024
        rel == "tokenizer/qwen_merges.txt" -> 1L * 1024 * 1024
        rel == "tokenizer/qwen_vocab.txt" -> 1L * 1024 * 1024
        rel == "tokenizer/qwen_special.txt" -> 100L
        rel == "tokenizer/tokenizer_fixture.txt" -> 500L
        rel == "host/time_guidance_embed_bf16.bin" -> 20L * 1024 * 1024
        else -> 1L
    }
}
