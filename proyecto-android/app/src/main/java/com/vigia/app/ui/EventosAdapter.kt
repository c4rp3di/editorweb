package com.vigia.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.vigia.app.store.DnsEventEntity
import com.vigia.app.util.Formato

class EventosAdapter(contexto: Context) :
    ArrayAdapter<DnsEventEntity>(contexto, android.R.layout.simple_list_item_2, android.R.id.text1) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val vista = super.getView(position, convertView, parent)
        val evento = getItem(position)
        val titulo = vista.findViewById<TextView>(android.R.id.text1)
        val detalle = vista.findViewById<TextView>(android.R.id.text2)
        if (evento != null) {
            titulo.text = evento.dominio
            detalle.text = Formato.hora(evento.ts) + " · " + evento.tipo + " · " + evento.app +
                "\n" + evento.respuesta
        }
        return vista
    }

    fun actualizar(lista: List<DnsEventEntity>) {
        setNotifyOnChange(false)
        clear()
        addAll(lista)
        notifyDataSetChanged()
    }
}
