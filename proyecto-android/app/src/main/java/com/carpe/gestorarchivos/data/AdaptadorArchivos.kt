package com.carpe.gestorarchivos.data

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.carpe.gestorarchivos.R

class AdaptadorArchivos(
    private val alTocar: (ArchivoItem) -> Unit,
    private val alMantener: (ArchivoItem, View) -> Unit,
    private val alCambiarSeleccion: (Set<String>) -> Unit
) : RecyclerView.Adapter<AdaptadorArchivos.VH>() {

    private val items = mutableListOf<ArchivoItem>()
    private val seleccionados = linkedSetOf<String>()
    private var modoSeleccion = false

    fun actualizar(nuevos: List<ArchivoItem>) {
        items.clear()
        items.addAll(nuevos)
        val claves = nuevos.map { it.clave }.toSet()
        seleccionados.retainAll(claves)
        modoSeleccion = seleccionados.isNotEmpty()
        notifyDataSetChanged()
        alCambiarSeleccion(seleccionados.toSet())
    }

    fun activarSeleccion(clave: String, activa: Boolean) {
        if (activa) seleccionados.add(clave) else seleccionados.remove(clave)
        modoSeleccion = seleccionados.isNotEmpty()
        val idx = items.indexOfFirst { it.clave == clave }
        if (idx >= 0) notifyItemChanged(idx)
        alCambiarSeleccion(seleccionados.toSet())
    }

    fun limpiarSeleccion() {
        seleccionados.clear()
        modoSeleccion = false
        notifyDataSetChanged()
        alCambiarSeleccion(emptySet())
    }

    fun alternarSeleccion(clave: String) {
        activarSeleccion(clave, !seleccionados.contains(clave))
    }

    fun obtenerSeleccionados(): List<ArchivoItem> =
        items.filter { seleccionados.contains(it.clave) }

    fun enModoSeleccion(): Boolean = modoSeleccion

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icono: TextView = v.findViewById(R.id.iconoItem)
        val nombre: TextView = v.findViewById(R.id.nombreItem)
        val detalle: TextView = v.findViewById(R.id.detalleItem)
        val check: CheckBox = v.findViewById(R.id.checkSeleccion)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_archivo, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val seleccionado = seleccionados.contains(item.clave)

        holder.nombre.text = item.nombre
        holder.icono.text = iconoPara(item)
        holder.detalle.text = if (item.esCarpeta) "Carpeta" else item.tamanoLegible
        holder.detalle.visibility = if (holder.detalle.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        holder.check.visibility = if (modoSeleccion) View.VISIBLE else View.GONE
        holder.check.isChecked = seleccionado

        holder.itemView.background = if (seleccionado) {
            holder.itemView.context.getDrawable(R.drawable.bg_item_seleccionado)
        } else {
            val out = android.util.TypedValue()
            holder.itemView.context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, out, true
            )
            holder.itemView.context.getDrawable(out.resourceId)
        }

        holder.itemView.setOnClickListener {
            if (modoSeleccion) alternarSeleccion(item.clave)
            else alTocar(item)
        }
        holder.itemView.setOnLongClickListener {
            if (!modoSeleccion) alternarSeleccion(item.clave)
            alMantener(item, it)
            true
        }
    }

    private fun iconoPara(item: ArchivoItem): String {
        if (item.esCarpeta) return "📁"
        val mime = item.mime ?: ""
        val ext = item.nombre.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") || ext in listOf("png","jpg","jpeg","gif","webp","bmp") -> "🖼️"
            mime.startsWith("video/") || ext in listOf("mp4","mkv","avi","mov","webm") -> "🎬"
            mime.startsWith("audio/") || ext in listOf("mp3","wav","ogg","m4a","flac") -> "🎵"
            ext in listOf("txt","md","log","json","xml","csv","html","css","js","kt","java") -> "📝"
            ext == "pdf" -> "📕"
            ext in listOf("zip","rar","7z","tar","gz") -> "🗜️"
            ext == "apk" -> "📦"
            else -> "📄"
        }
    }

    override fun getItemCount() = items.size
}