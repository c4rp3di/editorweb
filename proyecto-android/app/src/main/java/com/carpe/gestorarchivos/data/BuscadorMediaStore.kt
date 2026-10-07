package com.carpe.gestorarchivos.data

import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Consultas instantáneas al índice de archivos del sistema (solo ve lo que Android ha indexado). */
object BuscadorMediaStore {

    private val URI = MediaStore.Files.getContentUri("external")
    private const val DATA = MediaStore.Files.FileColumns.DATA
    private const val NOMBRE = MediaStore.Files.FileColumns.DISPLAY_NAME
    private const val FECHA = MediaStore.Files.FileColumns.DATE_MODIFIED

    private fun consultar(
        context: Context, seleccion: String, args: Array<String>, limite: Int
    ): List<ArchivoItem> {
        val resultado = ArrayList<ArchivoItem>()
        try {
            context.contentResolver.query(URI, arrayOf(DATA), seleccion, args, "$FECHA DESC")?.use { c ->
                val iData = c.getColumnIndexOrThrow(DATA)
                while (c.moveToNext() && resultado.size < limite) {
                    val ruta = c.getString(iData) ?: continue
                    val f = File(ruta)
                    if (f.isFile) resultado.add(ArchivoItem.desde(f))
                }
            }
        } catch (e: Exception) {
            // sin permiso o índice no disponible: se devuelve lo que haya
        }
        return resultado
    }

    fun modificadosRecientemente(context: Context, limite: Int): List<ArchivoItem> =
        consultar(
            context,
            "$DATA NOT LIKE ? AND $DATA NOT LIKE ?",
            arrayOf("%/.%", "%/Android/%"),
            limite
        )

    fun buscarPorNombre(context: Context, texto: String, limite: Int): List<ArchivoItem> {
        val escapado = texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return consultar(
            context,
            "$NOMBRE LIKE ? ESCAPE '\\' AND $DATA NOT LIKE ?",
            arrayOf("%$escapado%", "%/${Papelera.NOMBRE}/%"),
            limite
        )
    }
}
