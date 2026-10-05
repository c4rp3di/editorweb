package com.ejemplo.chat.ia.flux

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Flux2Files {
    private val graphs = listOf("kc_prep.tflite","kc_double0.tflite","kc_double1.tflite","kc_single0.tflite","kc_single1.tflite","kc_single2.tflite","kc_single3.tflite","kc_final.tflite","ke_enc0.tflite","ke_enc1.tflite","ke_enc2.tflite","kv_vae.tflite")
    fun isComplete(root: File): Boolean = graphs.all { File(root,it).length() > 0 } && File(root,"klein_bins/inputs_embeds.bin").exists()
    class Bins(root: File) {
        private val d = File(root,"klein_bins")
        val inputsEmbeds=readF("inputs_embeds"); val encMask=readF("enc_mask"); val encCos=readF("enc_cos"); val encSin=readF("enc_sin"); val cos=readF("cos"); val sin=readF("sin"); val temb=readF("temb"); val dsigma=readF("dsigma"); val bnMean=readF("bn_mean"); val bnStd=readF("bn_std"); val unpack=readI("unpack_perm"); val unpatch=readI("unpatch_perm"); val latents0=readF("latents0")
        private fun bytes(n:String)=File(d,"$n.bin").readBytes()
        private fun readF(n:String)=bytes(n).let { val b=ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN); FloatArray(it.size/4){b.float} }
        private fun readI(n:String)=bytes(n).let { val b=ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN); IntArray(it.size/4){b.int} }
    }
}
