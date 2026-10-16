package com.ejemplo.chat.ia

import android.media.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DiffusionOutput {
    data class ImageInfo(val width: Int, val height: Int, val channels: Int, val data: ByteArray)
    data class VideoInfo(val width: Int, val height: Int, val channels: Int, val frames: Int, val fps: Int, val data: ByteArray)

    fun decodeImage(file: File): ImageInfo {
        FileInputStream(file).use { input ->
            val h=ByteArray(28); if(input.read(h)!=28) error("CPIMG1: cabecera incompleta")
            require(String(h,0,6)=="CPIMG1") { "Salida nativa de imagen no reconocida" }
            val b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN); b.position(8)
            val w=b.int; val hh=b.int; val c=b.int; val n=b.long
            require(w>0&&hh>0&&c in 3..4&&n in 1..(w.toLong()*hh*c)) { "CPIMG1: dimensiones/tamaño inválidos" }
            val data=ByteArray(n.toInt()); var off=0; while(off<data.size){val r=input.read(data,off,data.size-off);if(r<0)error("CPIMG1 truncado");off+=r}
            return ImageInfo(w,hh,c,data)
        }
    }

    fun imageToPng(src: File, dst: File): ImageInfo {
        val i=decodeImage(src); val argb=IntArray(i.width*i.height)
        var p=0
        for(x in argb.indices){val r=i.data[p++].toInt() and 255; val g=i.data[p++].toInt() and 255; val bl=i.data[p++].toInt() and 255; val a=if(i.channels==4)i.data[p++].toInt() and 255 else 255; argb[x]=(a shl 24) or (r shl 16) or (g shl 8) or bl}
        val bmp=Bitmap.createBitmap(argb,i.width,i.height,Bitmap.Config.ARGB_8888)
        dst.outputStream().use { require(bmp.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bmp.recycle(); return i
    }

    fun decodeVideo(file: File): VideoInfo {
        FileInputStream(file).use { input ->
            val h=ByteArray(28); if(input.read(h)!=28) error("CPVID1: cabecera incompleta")
            require(String(h,0,6)=="CPVID1") { "Salida nativa de vídeo no reconocida" }
            val b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN); b.position(8)
            val w=b.int; val hh=b.int; val c=b.int; val frames=b.int; val fps=b.int
            require(w>0&&hh>0&&c in 3..4&&frames>0&&fps>0) { "CPVID1: cabecera inválida" }
            val bytesPerFrame=w.toLong()*hh*c; val total=bytesPerFrame*frames
            require(total<=Int.MAX_VALUE) { "Vídeo demasiado grande para el buffer Android" }
            val data=ByteArray(total.toInt()); var off=0; while(off<data.size){val r=input.read(data,off,data.size-off);if(r<0)error("CPVID1 truncado");off+=r}
            return VideoInfo(w,hh,c,frames,fps,data)
        }
    }

    fun videoToMp4(src: File, dst: File): VideoInfo {
        val v=decodeVideo(src)
        val codec=MediaCodec.createEncoderByType("video/avc")
        val formats=codec.codecInfo.getCapabilitiesForType("video/avc").colorFormats.toList()
        val color=when { formats.contains(21)->21; formats.contains(19)->19; else -> error("MediaCodec AVC no admite YUV420 planar/semi-planar: $formats") }
        val format=MediaFormat.createVideoFormat("video/avc",v.width,v.height).apply{
            setInteger(MediaFormat.KEY_COLOR_FORMAT,color); setInteger(MediaFormat.KEY_BIT_RATE,(v.width*v.height*5).coerceAtLeast(300_000)); setInteger(MediaFormat.KEY_FRAME_RATE,v.fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
        }
        codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val muxer=MediaMuxer(dst.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track=-1; var started=false; var frame=0; var eos=false; val timeout=10_000L
        try {
            while(!eos){
                val inIndex=codec.dequeueInputBuffer(timeout)
                if(inIndex>=0){
                    val buf=codec.getInputBuffer(inIndex) ?: error("input buffer nulo")
                    buf.clear()
                    if(frame<v.frames){
                        val yuv=rgbaToYuv(v,frame,color); buf.put(yuv); codec.queueInputBuffer(inIndex,0,yuv.size,frame*1_000_000L/v.fps,0); frame++
                    } else { codec.queueInputBuffer(inIndex,0,0,frame*1_000_000L/v.fps,MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos=true }
                }
                val info=MediaCodec.BufferInfo(); var out=codec.dequeueOutputBuffer(info,timeout)
                while(out>=0){
                    val ob=codec.getOutputBuffer(out)
                    if(info.size>0 && ob!=null && started){ob.position(info.offset);ob.limit(info.offset+info.size);muxer.writeSampleData(track,ob,info)}
                    codec.releaseOutputBuffer(out,false); out=codec.dequeueOutputBuffer(info,0)
                }
                if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !started){track=muxer.addTrack(codec.outputFormat);muxer.start();started=true}
            }
            if(!started) error("MediaCodec no produjo formato de salida")
        } finally { runCatching{codec.stop()}; runCatching{codec.release()}; runCatching{if(started)muxer.stop()}; runCatching{muxer.release()} }
        DebugLog.log("DIFFUSION", "MP4 creado · ${dst.name} · ${v.width}x${v.height} · ${v.frames} frames · ${v.fps} fps · ${dst.length()} bytes")
        return v
    }

    private fun rgbaToYuv(v: VideoInfo, frame:Int, color:Int): ByteArray {
        val frameBytes=v.width*v.height*v.channels; val base=frame*frameBytes; val y=ByteArray(v.width*v.height); val uv=ByteArray(v.width*v.height/2); var yi=0; var ui=0
        for(row in 0 until v.height){for(col in 0 until v.width){val p=base+(row*v.width+col)*v.channels; val r=v.data[p].toInt() and 255; val g=v.data[p+1].toInt() and 255; val b=v.data[p+2].toInt() and 255; y[yi++]=(0.257*r+0.504*g+0.098*b+16).toInt().coerceIn(0,255).toByte(); if((row and 1)==0&&(col and 1)==0){val u=(-0.148*r-0.291*g+0.439*b+128).toInt().coerceIn(0,255); val vv=(0.439*r-0.368*g-0.071*b+128).toInt().coerceIn(0,255); uv[ui++]=if(color==21)u.toByte() else vv.toByte(); uv[ui++]=if(color==21)vv.toByte() else u.toByte()}}}
        return y+uv
    }
}
