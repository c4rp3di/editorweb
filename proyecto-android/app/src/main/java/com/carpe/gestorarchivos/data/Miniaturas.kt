package com.carpe.gestorarchivos.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

object Miniaturas {

    private val cache = object : LruCache<String, Bitmap>(
        maxOf(1024, (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val ejecutor = Executors.newFixedThreadPool(3)
    private val principal = Handler(Looper.getMainLooper())

    private fun clave(f: File) = f.absolutePath + "|" + f.lastModified() + "|" + f.length()

    fun enCache(f: File): Bitmap? = cache.get(clave(f))

    /** Genera la miniatura en segundo plano; [vigente] permite descartar peticiones de filas ya recicladas. */
    fun pedir(f: File, esVideo: Boolean, px: Int, vigente: () -> Boolean, alListo: (Bitmap?) -> Unit) {
        ejecutor.execute {
            if (!vigente()) return@execute
            val bmp = try {
                if (esVideo) decodificarVideo(f, px) else decodificarImagen(f, px)
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) cache.put(clave(f), bmp)
            principal.post { alListo(bmp) }
        }
    }

    private fun decodificarImagen(f: File, px: Int): Bitmap? {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, limites)
        if (limites.outWidth <= 0 || limites.outHeight <= 0) return null
        var s = 1
        while (limites.outWidth / (s * 2) >= px && limites.outHeight / (s * 2) >= px) s *= 2
        val opciones = BitmapFactory.Options().apply { inSampleSize = s }
        val b = BitmapFactory.decodeFile(f.absolutePath, opciones) ?: return null
        return ThumbnailUtils.extractThumbnail(b, px, px)
    }

    private fun decodificarVideo(f: File, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
            frame?.let { ThumbnailUtils.extractThumbnail(it, px, px) }
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }
}
