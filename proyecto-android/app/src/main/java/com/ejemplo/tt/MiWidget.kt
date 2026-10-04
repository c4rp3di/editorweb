package com.ejemplo.tt

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

// Widget de ejemplo. Cada vez que Android decide refrescarlo (ver
// updatePeriodMillis en res/xml/mi_widget_info.xml) llama a onUpdate.
class MiWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, gestorWidgets: AppWidgetManager, idsWidgets: IntArray) {
        for (idWidget in idsWidgets) {
            val vistas = RemoteViews(context.packageName, R.layout.widget_mi_widget)
            vistas.setTextViewText(R.id.textoWidget, "¡Hola desde el widget!")
            gestorWidgets.updateAppWidget(idWidget, vistas)
        }
    }
}
