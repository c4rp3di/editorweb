package com.carpe.descompresor.motor

import android.content.Context
import android.net.Uri
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.CopiaCancelada
import com.carpe.descompresor.util.CopiaLocal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object CatalogoMotores {
    val motores: List<MotorContenedor> = listOf(
        MotorApk(), MotorZip(),
        MotorTarGz(), MotorTarBz2(), MotorTarXz(), MotorTar(),
        MotorGzip(), MotorBzip2(), MotorXz(),
        MotorSieteZ(), MotorRar(), MotorCpio(), MotorAr()
    )

    /** Copia la Uri a caché (con progreso y cancelación) y abre el contenedor. */
    suspend fun abrir(
        ctx: Context,
        uri: Uri,
        maxBytes: Long,
        progreso: ((Long, Long) -> Unit)?,
        cancelado: (() -> Boolean)?
    ): Contenedor = withContext(Dispatchers.IO) {
        val copia = CopiaLocal.copiar(ctx, uri, maxBytes, progreso, cancelado)
        try {
            if (cancelado != null && cancelado()) throw CopiaCancelada()
            abrirArchivo(ctx, copia.archivo, copia.nombre)
        } catch (t: Throwable) {
            copia.archivo.delete()
            throw t
        }
    }

    /** Abre un archivo local ya copiado (quien llama borra el archivo si esto falla). */
    suspend fun abrirArchivo(ctx: Context, archivo: File, nombre: String): Contenedor {
        val cab = CopiaLocal.cabecera(archivo, 512)
        val motor = motores.firstOrNull { it.puedeAbrir(cab, nombre) }
            ?: throw IOException("Formato no reconocido: $nombre")
        return motor.abrirArchivo(ctx, archivo, nombre)
    }
}
