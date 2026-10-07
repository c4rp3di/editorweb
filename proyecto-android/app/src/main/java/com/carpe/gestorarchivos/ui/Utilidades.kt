package com.carpe.gestorarchivos.ui

import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.carpe.gestorarchivos.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun Fragment.toast(mensaje: String, largo: Boolean = false) {
    val ctx = context ?: return
    Toast.makeText(ctx, mensaje, if (largo) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

fun Fragment.navegarA(destino: Fragment, etiqueta: String) {
    parentFragmentManager.beginTransaction()
        .replace(R.id.contenedorPantallas, destino)
        .addToBackStack(etiqueta)
        .commit()
}

fun Fragment.volverAtras() {
    if (isAdded) parentFragmentManager.popBackStack()
}

class DialogoProgreso(private val dialogo: AlertDialog, private val texto: TextView) {
    fun actualizar(mensaje: String) {
        texto.post { texto.text = mensaje }
    }

    fun cerrar() {
        if (dialogo.isShowing) dialogo.dismiss()
    }
}

fun Fragment.mostrarProgreso(titulo: String): DialogoProgreso {
    val vista = layoutInflater.inflate(R.layout.dialog_progreso, null)
    val texto = vista.findViewById<TextView>(R.id.textoProgreso)
    val dialogo = AlertDialog.Builder(requireContext())
        .setTitle(titulo)
        .setView(vista)
        .setCancelable(false)
        .create()
    dialogo.show()
    return DialogoProgreso(dialogo, texto)
}

/** Ejecuta [trabajo] fuera del hilo principal mostrando un diálogo de progreso; [alTerminar] corre en el hilo principal. */
fun <T> Fragment.ejecutarConProgreso(
    titulo: String,
    trabajo: suspend (DialogoProgreso) -> T,
    alTerminar: (Result<T>) -> Unit
) {
    val dialogo = mostrarProgreso(titulo)
    viewLifecycleOwner.lifecycleScope.launch {
        var resultado: Result<T>? = null
        try {
            resultado = withContext(Dispatchers.IO) { runCatching { trabajo(dialogo) } }
        } finally {
            dialogo.cerrar()
        }
        resultado?.let { alTerminar(it) }
    }
}

/** Comparación «natural»: archivo2 < archivo10. */
fun compararNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ie = i
            while (ie < a.length && a[ie].isDigit()) ie++
            var je = j
            while (je < b.length && b[je].isDigit()) je++
            val na = a.substring(i, ie).trimStart('0')
            val nb = b.substring(j, je).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ie
            j = je
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
