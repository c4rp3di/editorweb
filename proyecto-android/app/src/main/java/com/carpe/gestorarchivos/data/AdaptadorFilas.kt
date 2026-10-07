package com.carpe.gestorarchivos.data

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.carpe.gestorarchivos.R

data class Fila(
    val id: String,
    val icono: String,
    val titulo: String,
    val subtitulo: String = "",
    val progreso: Int = -1,
    val marcable: Boolean = false,
    var marcada: Boolean = false,
    val cabecera: Boolean = false,
    val dato: Any? = null
)

class AdaptadorFilas(
    private val alTocar: (Fila) -> Unit = {},
    private val alMarcar: (Fila) -> Unit = {}
) : RecyclerView.Adapter<AdaptadorFilas.VH>() {

    val filas = mutableListOf<Fila>()

    fun establecer(nuevas: List<Fila>) {
        filas.clear()
        filas.addAll(nuevas)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.filaCheck)
        val icono: TextView = v.findViewById(R.id.filaIcono)
        val titulo: TextView = v.findViewById(R.id.filaTitulo)
        val subtitulo: TextView = v.findViewById(R.id.filaSubtitulo)
        val progreso: ProgressBar = v.findViewById(R.id.filaProgreso)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_fila, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val fila = filas[position]
        holder.icono.text = fila.icono
        holder.titulo.text = fila.titulo
        holder.titulo.setTypeface(null, if (fila.cabecera) Typeface.BOLD else Typeface.NORMAL)
        holder.subtitulo.text = fila.subtitulo
        holder.subtitulo.visibility = if (fila.subtitulo.isEmpty()) View.GONE else View.VISIBLE
        if (fila.progreso >= 0) {
            holder.progreso.visibility = View.VISIBLE
            holder.progreso.progress = fila.progreso
        } else {
            holder.progreso.visibility = View.GONE
        }
        holder.check.visibility = if (fila.marcable) View.VISIBLE else View.GONE
        holder.check.isChecked = fila.marcada
        if (fila.cabecera) {
            holder.itemView.setBackgroundColor(holder.itemView.context.getColor(R.color.seleccion_fondo))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val f = filas[pos]
            if (f.marcable) {
                f.marcada = !f.marcada
                notifyItemChanged(pos)
                alMarcar(f)
            }
            alTocar(f)
        }
    }

    override fun getItemCount() = filas.size
}
