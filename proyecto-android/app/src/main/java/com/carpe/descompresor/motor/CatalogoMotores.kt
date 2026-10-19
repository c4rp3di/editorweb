package com.carpe.descompresor.motor

import android.content.Context
import android.net.Uri
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.util.CopiaLocal
import com.carpe.descompresor.util.PreferenciasApp
import com.carpe.descompresor.util.Seguridad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object CatalogoMotores {
    val motores: List<MotorContenedor> = listOf(
        MotorApk(), MotorZip(),
        MotorTarGz(), MotorTarBz2(), MotorTarXz(), MotorTar(),
        MotorGzip(), MotorBzip2(), MotorXz(),
        MotorSieteZ(), MotorRar(), MotorCpio(), MotorAr()
    )

    suspend fun abrir(ctx: Context, uri: Uri): Contenedor = withContext(Dispatchers.IO) {
        val lim = Seguridad.limites(PreferenciasApp(ctx))
        val copia = CopiaLocal.copiar(ctx, uri, lim.maxContenedorBytes)
        try {
            val cab = CopiaLocal.cabecera(copia.archivo, 512)
            val motor = motores.firstOrNull { it.puedeAbrir(cab, copia.nombre) }
                ?: throw IOException("Formato no reconocido: ${copia.nombre}")
            motor.abrirArchivo(ctx, copia.archivo, copia.nombre)
        } catch (t: Throwable) {
            copia.archivo.delete()
            throw t
        }
    }
}
