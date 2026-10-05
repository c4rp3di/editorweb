package com.ejemplo.chat.ia.flux

import java.io.File

object Flux2Files {
    const val MODEL_ID = "flux2-klein-4b"

    // Solo los grafos texto→imagen. Los kce*/kce2*/kv_vae_enc son de edición de imagen:
    // la app no los descarga ni los usa, y exigirlos impedía que el modelo figurase como «listo».
    val graphs = listOf(
        "kc_prep.tflite", "kc_double0.tflite", "kc_double1.tflite",
        "kc_single0.tflite", "kc_single1.tflite", "kc_single2.tflite", "kc_single3.tflite", "kc_final.tflite",
        "ke_enc0.tflite", "ke_enc1.tflite", "ke_enc2.tflite", "kv_vae.tflite"
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

    // Downloads use .part and are renamed only after EOF. Avoid guessed
    // per-file floors; the repository reports ~381 MB total for all kc_* graphs.
    fun minimumBytes(rel: String): Long = if (rel.endsWith(".tflite")) 1_000_000L else 1L

    /** Tamaño exacto y magia FlatBuffer ("TFL3") de cada grafo, para detectar descargas truncadas o corruptas. */
    fun describeGraphs(root: File): List<String> = graphs.map { name ->
        val f = File(root, name)
        val magic = runCatching {
            f.inputStream().use { ins -> val b = ByteArray(8); ins.read(b); String(b, 4, 4, Charsets.ISO_8859_1) }
        }.getOrDefault("?")
        "$name ${f.length()} B magic=$magic"
    }

}
