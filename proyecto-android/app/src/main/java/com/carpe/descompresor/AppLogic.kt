package com.carpe.descompresor

import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import com.carpe.descompresor.ui.ContenedorViewModel
import com.carpe.descompresor.ui.Lanzadores
import com.carpe.descompresor.ui.PantallaAjustes
import com.carpe.descompresor.ui.PantallaApk
import com.carpe.descompresor.ui.PantallaLista
import com.carpe.descompresor.ui.Ui
import com.carpe.descompresor.util.CopiaLocal
import com.carpe.descompresor.util.PreferenciasApp
import com.google.android.material.bottomnavigation.BottomNavigationView

// Punto de entrada de la app: MainActivity llama a onIniciar() al terminar de arrancar.
object AppLogic {
    fun onIniciar(activity: MainActivity) {
        Log.i("Descompresor", "Arranque OK · com.carpe.descompresor")

        val prefs = PreferenciasApp(activity)
        prefs.inicializarSiHaceFalta(activity)
        Ui.escala = prefs.entero("escala_fuente") / 100f
        AppCompatDelegate.setDefaultNightMode(
            when (prefs.cadena("tema")) {
                "claro" -> AppCompatDelegate.MODE_NIGHT_NO
                "oscuro" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        val vm = ViewModelProvider(activity)[ContenedorViewModel::class.java]
        if (vm.contenedor == null && !vm.cargando) CopiaLocal.limpiarAbiertos(activity)

        val lanz = Lanzadores(activity)
        val marco = activity.findViewById<FrameLayout>(R.id.contenedor_principal)
        val nav = activity.findViewById<BottomNavigationView>(R.id.barra_inferior)

        val lista = PantallaLista(activity, vm, lanz)
        val apk = PantallaApk(activity, vm, lanz)
        val ajustes = PantallaAjustes(activity, vm, lanz)

        marco.removeAllViews()
        marco.addView(lista.vista)
        marco.addView(apk.vista)
        marco.addView(ajustes.vista)

        fun mostrar(cual: View) {
            lista.vista.visibility = if (cual === lista.vista) View.VISIBLE else View.GONE
            apk.vista.visibility = if (cual === apk.vista) View.VISIBLE else View.GONE
            ajustes.vista.visibility = if (cual === ajustes.vista) View.VISIBLE else View.GONE
        }
        mostrar(lista.vista)

        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_contenedor -> mostrar(lista.vista)
                R.id.nav_apk -> mostrar(apk.vista)
                R.id.nav_ajustes -> mostrar(ajustes.vista)
            }
            true
        }

        lanz.pedirNotificaciones()
    }
}
