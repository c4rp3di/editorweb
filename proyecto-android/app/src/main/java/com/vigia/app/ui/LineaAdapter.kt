package com.vigia.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.vigia.app.core.FilaLinea
import com.vigia.app.util.Formato

class LineaAdapter(contexto: Context) :
    ArrayAdapter<FilaLinea>(contexto, android.R.layout.simple_list_item_2, android.R.id.text1) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val vista = super.getView(position, convertView, parent)
        val fila = getItem(position)
        val titulo = vista.findViewById<TextView>(android.R.id.text1)
        val detalle = vista.findViewById<TextView>(android.R.id.text2)
        if (fila != null) {
            titulo.text = Formato.hora(fila.ts) + " · " + fila.cat + " · " + fila.tipo
            val app = if (fila.app.isEmpty()) "" else fila.app + "\n"
            detalle.text = app + fila.texto
        }
        return vista
    }

    fun actualizar(lista: List<FilaLinea>) {
        setNotifyOnChange(false)
        clear()
        addAll(lista)
        notifyDataSetChanged()
    }
}
