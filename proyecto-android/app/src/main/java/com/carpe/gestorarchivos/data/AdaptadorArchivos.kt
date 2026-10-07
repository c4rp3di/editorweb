package com.carpe.gestorarchivos.data

import android.graphics.Bitmap
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.carpe.gestorarchivos.R

object Iconos {
    fun de(categoria: Categoria): String = when (categoria) {
        Categoria.CARPETA -> "📁"
        Categoria.IMAGEN -> "🖼️"
        Categoria.VIDEO -> "🎬"
        Categoria.AUDIO -> "🎵"
        Categoria.TEXTO -> "📝"
        Categoria.PDF -> "📕"
        Categoria.DOCUMENTO -> "📘"
        Categoria.HOJA -> "📗"
        Categoria.PRESENTACION -> "📙"
        Categoria.COMPRIMIDO -> "🗜️"
        Categoria.APK -> "📦"
        Categoria.OTRO -> "📄"
    }
}

class AdaptadorArchivos(
    private val alTocar: (ArchivoItem) -> Unit,
    private val alCambiarSeleccion: (Set<String>) -> Unit
) : RecyclerView.Adapter<AdaptadorArchivos.VH>() {

    private val items = mutableListOf<ArchivoItem>()
    private val seleccionados = linkedSetOf<String>()
    private var modoSeleccion = false
    private var pxMiniatura = 120

    /** Si se define, la pulsación larga llama a esto en lugar de entrar en modo selección. */
    var alMantener: ((ArchivoItem) -> Unit)? = null

    /** Botón ⋮ que aparece en los elementos seleccionados. */
    var alPulsarMas: ((ArchivoItem, View) -> Unit)? = null

    /** Muestra la carpeta contenedora en el detalle (útil en resultados de búsqueda recursiva). */
    var mostrarRutaPadre = false

    fun actualizar(nuevos: List<ArchivoItem>) {
        items.clear()
        items.addAll(nuevos)
        val claves = nuevos.map { it.clave }.toSet()
        seleccionados.retainAll(claves)
        modoSeleccion = seleccionados.isNotEmpty()
        notifyDataSetChanged()
        alCambiarSeleccion(seleccionados.toSet())
    }

    fun obtenerItems(): List<ArchivoItem> = items.toList()

    private fun activarSeleccion(clave: String, activa: Boolean) {
        if (activa) seleccionados.add(clave) else seleccionados.remove(clave)
        modoSeleccion = seleccionados.isNotEmpty()
        notifyDataSetChanged()
        alCambiarSeleccion(seleccionados.toSet())
    }

    fun limpiarSeleccion() {
        seleccionados.clear()
        modoSeleccion = false
        notifyDataSetChanged()
        alCambiarSeleccion(emptySet())
    }

    fun seleccionarTodo() {
        items.forEach { seleccionados.add(it.clave) }
        modoSeleccion = seleccionados.isNotEmpty()
        notifyDataSetChanged()
        alCambiarSeleccion(seleccionados.toSet())
    }

    fun alternarSeleccion(clave: String) {
        activarSeleccion(clave, !seleccionados.contains(clave))
    }

    fun obtenerSeleccionados(): List<ArchivoItem> =
        items.filter { seleccionados.contains(it.clave) }

    fun enModoSeleccion(): Boolean = modoSeleccion

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icono: TextView = v.findViewById(R.id.iconoItem)
        val miniatura: ImageView = v.findViewById(R.id.miniaturaItem)
        val nombre: TextView = v.findViewById(R.id.nombreItem)
        val detalle: TextView = v.findViewById(R.id.detalleItem)
        val check: CheckBox = v.findViewById(R.id.checkSeleccion)
        val mas: TextView = v.findViewById(R.id.botonMasItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        pxMiniatura = (40 * parent.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_archivo, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val seleccionado = seleccionados.contains(item.clave)
        val categoria = item.categoria

        holder.nombre.text = item.nombre
        holder.icono.text = Iconos.de(categoria)

        holder.detalle.tag = item.clave
        val fecha = if (item.ultimaModificacion > 0) Formato.fechaHora(item.ultimaModificacion) else ""
        val ruta = if (mostrarRutaPadre) item.archivo.parent else null
        if (item.esCarpeta) {
            val resumen = ResumenCarpetas.enCache(item.archivo)
            holder.detalle.text = detalleCarpeta(
                if (resumen != null) ResumenCarpetas.descripcion(resumen) else "Calculando…", fecha, ruta
            )
            if (resumen == null) {
                ResumenCarpetas.pedir(item.archivo, { holder.detalle.tag == item.clave }) { r ->
                    if (holder.detalle.tag == item.clave) {
                        holder.detalle.text = detalleCarpeta(ResumenCarpetas.descripcion(r), fecha, ruta)
                    }
                }
            }
        } else {
            holder.detalle.text = detalleCarpeta(item.tamanoLegible, fecha, ruta)
        }

        holder.check.visibility = if (modoSeleccion) View.VISIBLE else View.GONE
        holder.check.isChecked = seleccionado

        // Miniaturas de imágenes y vídeos
        holder.miniatura.tag = item.clave
        if (categoria == Categoria.IMAGEN || categoria == Categoria.VIDEO) {
            val enCache = Miniaturas.enCache(item.archivo)
            if (enCache != null) {
                mostrarMiniatura(holder, enCache)
            } else {
                holder.miniatura.visibility = View.GONE
                holder.icono.visibility = View.VISIBLE
                Miniaturas.pedir(
                    item.archivo, categoria == Categoria.VIDEO, pxMiniatura,
                    { holder.miniatura.tag == item.clave }
                ) { bmp ->
                    if (bmp != null && holder.miniatura.tag == item.clave) mostrarMiniatura(holder, bmp)
                }
            }
        } else {
            holder.miniatura.visibility = View.GONE
            holder.icono.visibility = View.VISIBLE
        }

        holder.itemView.background = if (seleccionado) {
            ContextCompat.getDrawable(holder.itemView.context, R.drawable.bg_item_seleccionado)
        } else {
            val out = TypedValue()
            holder.itemView.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            ContextCompat.getDrawable(holder.itemView.context, out.resourceId)
        }

        holder.itemView.setOnClickListener {
            if (modoSeleccion) alternarSeleccion(item.clave) else alTocar(item)
        }
        holder.itemView.setOnLongClickListener {
            val m = alMantener
            if (m != null) m(item) else alternarSeleccion(item.clave)
            true
        }

        holder.mas.visibility = if (seleccionado && alPulsarMas != null) View.VISIBLE else View.GONE
        holder.mas.setOnClickListener { alPulsarMas?.invoke(item, holder.mas) }
    }

    private fun detalleCarpeta(principal: String, fecha: String, ruta: String?): String {
        val partes = ArrayList<String>()
        if (principal.isNotEmpty()) partes.add(principal)
        if (fecha.isNotEmpty()) partes.add(fecha)
        if (ruta != null) partes.add(ruta)
        return partes.joinToString(" · ")
    }

    private fun mostrarMiniatura(holder: VH, bmp: Bitmap) {
        holder.miniatura.setImageBitmap(bmp)
        holder.miniatura.visibility = View.VISIBLE
        holder.icono.visibility = View.INVISIBLE
    }

    override fun getItemCount() = items.size
}
