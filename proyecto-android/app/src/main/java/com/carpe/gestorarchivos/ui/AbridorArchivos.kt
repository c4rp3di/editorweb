package com.carpe.gestorarchivos.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.carpe.gestorarchivos.data.ArchivoItem
import com.carpe.gestorarchivos.data.Categoria
import com.carpe.gestorarchivos.data.Formato
import com.carpe.gestorarchivos.data.GestorArchivos
import com.carpe.gestorarchivos.data.RepositorioRecientes
import java.io.File

/** Decide cómo se abre cada tipo de archivo (visores propios, navegador o apps externas). Compartido por el gestor y el buscador. */
class AbridorArchivos(
    private val fragment: Fragment,
    private val alIrACarpeta: (File) -> Unit,
    private val alZip: ((ArchivoItem) -> Unit)? = null
) {
    private val ctx: Context get() = fragment.requireContext()

    private fun esHtml(item: ArchivoItem) = item.extension == "html" || item.extension == "htm"

    fun abrir(item: ArchivoItem, vecinos: List<ArchivoItem> = emptyList()) {
        if (!item.esCarpeta) RepositorioRecientes(ctx).registrarArchivo(item.archivo)
        when (item.categoria) {
            Categoria.CARPETA -> alIrACarpeta(item.archivo)
            Categoria.TEXTO -> if (esHtml(item)) abrirHtml(item.archivo) else abrirEditor(item.archivo)
            Categoria.IMAGEN -> abrirImagen(item, vecinos)
            Categoria.VIDEO, Categoria.AUDIO ->
                fragment.navegarA(PantallaReproductor.nueva(item.archivo.absolutePath), "reproductor")
            Categoria.PDF ->
                fragment.navegarA(PantallaVisorPdf.nueva(item.archivo.absolutePath), "pdf")
            Categoria.DOCUMENTO, Categoria.HOJA ->
                if (item.extension == "docx" || item.extension == "xlsx" || item.extension == "xlsm") {
                    fragment.navegarA(PantallaVisorDocumento.nueva(item.archivo.absolutePath), "documento")
                } else {
                    abrirConSelector(item.archivo)
                }
            Categoria.COMPRIMIDO -> {
                val zip = alZip
                if (item.extension == "zip" && zip != null) zip(item) else abrirConSelector(item.archivo)
            }
            else -> abrirConSelector(item.archivo)
        }
    }

    /** «Abrir con…»: para HTML ofrece navegador, vista previa o editor; para el resto, el selector de apps. */
    fun abrirCon(item: ArchivoItem) {
        if (item.esCarpeta) return
        RepositorioRecientes(ctx).registrarArchivo(item.archivo)
        if (!esHtml(item)) {
            abrirConSelector(item.archivo)
            return
        }
        val f = item.archivo
        AlertDialog.Builder(ctx)
            .setTitle("Abrir «${item.nombre}» con")
            .setItems(
                arrayOf(
                    "🌐 Navegador",
                    "📄 Vista previa en la app (sin JavaScript)",
                    "✏️ Editor de texto",
                    "Otras apps…"
                )
            ) { _, i ->
                when (i) {
                    0 -> abrirHtml(f)
                    1 -> vistaPreviaHtml(f)
                    2 -> abrirEditor(f)
                    else -> abrirConSelector(f)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun abrirConSelector(archivo: File) {
        if (!GestorArchivos.abrirConOtraApp(ctx, archivo)) fragment.toast("No hay app para abrir este tipo de archivo")
    }

    fun abrirHtml(archivo: File) {
        if (!GestorArchivos.abrirEnNavegador(ctx, archivo)) {
            fragment.toast("No se pudo abrir en el navegador; se muestra la vista previa")
            vistaPreviaHtml(archivo)
        }
    }

    fun vistaPreviaHtml(archivo: File) {
        fragment.navegarA(PantallaVisorDocumento.nueva(archivo.absolutePath), "html")
    }

    fun abrirEditor(archivo: File) {
        val tam = archivo.length()
        when {
            tam > 10L * 1024 * 1024 ->
                fragment.toast("Archivo demasiado grande para editar (${Formato.tamano(tam)}). Máximo 10 MB.", true)
            tam > 2L * 1024 * 1024 ->
                AlertDialog.Builder(ctx)
                    .setTitle("Archivo grande")
                    .setMessage("Este archivo ocupa ${Formato.tamano(tam)}. Editarlo puede ir lento.")
                    .setPositiveButton("Abrir de todas formas") { _, _ ->
                        fragment.navegarA(PantallaEditorTexto.nueva(archivo.absolutePath), "editor")
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            else -> fragment.navegarA(PantallaEditorTexto.nueva(archivo.absolutePath), "editor")
        }
    }

    private fun abrirImagen(item: ArchivoItem, vecinos: List<ArchivoItem>) {
        val imagenes = vecinos.filter { it.categoria == Categoria.IMAGEN }
        var indice = imagenes.indexOfFirst { it.clave == item.clave }
        var lista = imagenes
        if (indice < 0) {
            lista = listOf(item)
            indice = 0
        } else if (lista.size > 300) {
            val desde = (indice - 150).coerceAtLeast(0)
            val hasta = (desde + 300).coerceAtMost(lista.size)
            lista = lista.subList(desde, hasta)
            indice -= desde
        }
        fragment.navegarA(PantallaVisorImagen.nueva(lista.map { it.archivo.absolutePath }, indice), "imagen")
    }
}
