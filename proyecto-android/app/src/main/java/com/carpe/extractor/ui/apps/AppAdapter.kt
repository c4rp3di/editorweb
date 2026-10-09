package com.carpe.panoptes.ui.apps

import android.content.pm.PackageInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.carpe.panoptes.R
import com.carpe.panoptes.ingest.ApkExtractor

class AppAdapter(private val onClick: (PackageInfo) -> Unit) :
    RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private var items: List<ApkExtractor.InstalledApp> = emptyList()

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val icono: ImageView = v.findViewById(R.id.iconoApp)
        val nombre: TextView = v.findViewById(R.id.nombreApp)
        val paquete: TextView = v.findViewById(R.id.paqueteApp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.icono.setImageDrawable(item.icono)
        holder.nombre.text = item.nombre
        holder.paquete.text = item.paquete
        holder.itemView.setOnClickListener { onClick(item.info) }
    }

    override fun getItemCount(): Int = items.size

    fun actualizar(nuevos: List<ApkExtractor.InstalledApp>) {
        items = nuevos
        notifyDataSetChanged()
    }
}
