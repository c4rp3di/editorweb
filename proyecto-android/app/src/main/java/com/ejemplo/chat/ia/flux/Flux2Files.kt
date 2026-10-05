package com.ejemplo.chat.ia.flux

import java.io.File

object Flux2Files {
    const val MODEL_ID = "flux2-klein-4b"

    val graphs: List<String> = listOf(
        "ke_enc0.tflite", "ke_enc1.tflite", "ke_enc2.tflite",
        "kc_prep.tflite", "kc_double0.tflite", "kc_double1.tflite",
        "kc_single0.tflite", "kc_single1.tflite", "kc_single2.tflite", "kc_single3.tflite",
        "kc_final.tflite", "kv_vae.tflite"
    )
    val tokenizerFiles: List<String> = listOf(
        "tokenizer/qwen_embed_fp16.bin", "tokenizer/qwen_merges.txt", "tokenizer/qwen_special.txt",
        "tokenizer/qwen_vocab.txt", "tokenizer/tokenizer_fixture.txt"
    )
    val requiredFiles = graphs + tokenizerFiles

    fun isComplete(root: File): Boolean = missing(root).isEmpty() && hasHostData(root)

    fun missing(root: File): List<String> = requiredFiles.filter { rel ->
        val f = File(root, rel)
        !f.isFile || f.length() < minimumBytes(rel)
    }

    fun hasHostData(root: File): Boolean =
        findTimeEmbedding(root) != null && findBn(root, "mean") != null &&
            (findBn(root, "std") != null || findBn(root, "var") != null)

    fun findTimeEmbedding(root: File): File? {
        val dir = File(root, "host")
        if (!dir.isDirectory) return null
        return dir.listFiles()?.filter { it.isFile }
            ?.firstOrNull { elementCount(it) == 4L * 3072L && (it.name.contains("time", true) || it.name.contains("guidance", true)) }
            ?: dir.listFiles()?.firstOrNull { it.isFile && elementCount(it) == 4L * 3072L }
    }

    fun findBn(root: File, kind: String): File? {
        val dir = File(root, "host")
        if (!dir.isDirectory) return null
        return dir.listFiles()?.firstOrNull {
            it.isFile && elementCount(it) == 128L && it.name.contains(kind, true)
        }
    }

    fun elementCount(file: File): Long {
        if (file.name.contains("bf16", ignoreCase = true)) return file.length() / 2L
        return if (file.length() % 4L == 0L) file.length() / 4L else -1L
    }

    fun minimumBytes(rel: String): Long = when (rel) {
        "ke_enc0.tflite", "ke_enc1.tflite", "ke_enc2.tflite" -> 850L * 1024 * 1024
        "kc_prep.tflite" -> 150L * 1024 * 1024
        "kc_double0.tflite" -> 650L * 1024 * 1024
        "kc_double1.tflite" -> 430L * 1024 * 1024
        "kc_single0.tflite", "kc_single1.tflite", "kc_single2.tflite", "kc_single3.tflite" -> 540L * 1024 * 1024
        "kc_final.tflite" -> 15L * 1024 * 1024
        "kv_vae.tflite" -> 40L * 1024 * 1024
        "tokenizer/qwen_embed_fp16.bin" -> 700L * 1024 * 1024
        "tokenizer/qwen_merges.txt" -> 1L * 1024 * 1024
        "tokenizer/qwen_vocab.txt" -> 1L * 1024 * 1024
        "tokenizer/qwen_special.txt" -> 100L
        "tokenizer/tokenizer_fixture.txt" -> 500L
        else -> 1L
    }
}
