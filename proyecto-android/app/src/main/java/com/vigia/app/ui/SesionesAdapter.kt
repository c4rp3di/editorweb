package com.vigia.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.vigia.app.core.SessionManager
import com.vigia.app.store.SessionEntity
import com.vigia.app.util.Formato

class SesionesAdapter(contexto: Context) :
    ArrayAdapter<SessionEntity>(contexto, android.R.layout.simple_list_item_2, android.R.id.text1) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val vista = super.getView(position, convertView, parent)
        val sesion = getItem(position)
        val titulo = vista.findViewById<TextView>(android.R.id.text1)
        val detalle = vista.findViewById<TextView>(android.R.id.text2)
        if (sesion != null) {
            titulo.text = "Sesión #" + sesion.id + " · " + Formato.fecha(sesion.inicio)
            val fin = sesion.fin
            val captura = if (SessionManager.vpnDe(sesion.configuracion)) "DNS" else "sin captura"
            detalle.text = if (fin == null) {
                "● En curso · " + captura
            } else {
                "Cerrada · " + Formato.duracion(fin - sesion.inicio) + " · " + captura
            }
        }
        return vista
    }

    fun actualizar(lista: List<SessionEntity>) {
        setNotifyOnChange(false)
        clear()
        addAll(lista)
        notifyDataSetChanged()
    }
}
