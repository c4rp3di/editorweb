package com.vigia.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.vigia.app.analyze.Severidad
import com.vigia.app.store.FindingEntity
import com.vigia.app.util.Formato

class HallazgosAdapter(contexto: Context) :
    ArrayAdapter<FindingEntity>(contexto, android.R.layout.simple_list_item_2, android.R.id.text1) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val vista = super.getView(position, convertView, parent)
        val h = getItem(position)
        val titulo = vista.findViewById<TextView>(android.R.id.text1)
        val detalle = vista.findViewById<TextView>(android.R.id.text2)
        if (h != null) {
            titulo.text = "[" + Severidad.etiqueta(h.severidad) + " · " + h.regla + " · " + h.confianza + "%] " + h.titulo
            val app = if (h.app.isEmpty()) "" else " · " + h.app
            val resumen = if (h.descripcion.length > 150) h.descripcion.substring(0, 150) + "…" else h.descripcion
            detalle.text = Formato.fecha(h.ts) + app + "\n" + resumen
        }
        return vista
    }

    fun actualizar(lista: List<FindingEntity>) {
        setNotifyOnChange(false)
        clear()
        addAll(lista)
        notifyDataSetChanged()
    }
}
