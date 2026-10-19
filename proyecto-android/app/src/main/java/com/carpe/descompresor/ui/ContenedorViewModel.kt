package com.carpe.descompresor.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carpe.descompresor.apk.CambiosApk
import com.carpe.descompresor.data.Contenedor
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.motor.CatalogoMotores
import com.carpe.descompresor.util.CopiaLocal
import com.carpe.descompresor.util.Iconos
import com.carpe.descompresor.util.PreferenciasApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Nodo(val ruta: String, val nombre: String, val esDir: Boolean, val entrada: Entrada?, val nivel: Int) {
    val hijos = ArrayList<Nodo>()
    var tamano: Long = 0L
    var archivos: List<Nodo> = emptyList()
    var nombreMostrado: String = nombre
}

object ArbolBuilder {
    fun construir(entradas: List<Entrada>): Nodo {
        val raiz = Nodo("", "", true, null, -1)
        val dirs = HashMap<String, Nodo>()
        dirs[""] = raiz

        fun dir(ruta: String): Nodo {
            val existente = dirs[ruta]
            if (existente != null) return existente
            val i = ruta.lastIndexOf('/')
            val padre = dir(if (i < 0) "" else ruta.substring(0, i))
            val n = Nodo(ruta, ruta.substring(i + 1), true, null, padre.nivel + 1)
            padre.hijos.add(n)
            dirs[ruta] = n
            return n
        }

        for (e in entradas) {
            val r = e.ruta.replace('\\', '/').trim('/')
            if (r.isEmpty()) continue
            if (e.esDirectorio) {
                dir(r)
                continue
            }
            val i = r.lastIndexOf('/')
            val padre = dir(if (i < 0) "" else r.substring(0, i))
            padre.hijos.add(Nodo(r, r.substring(i + 1), false, e, padre.nivel + 1))
        }
        cerrar(raiz)
        return raiz
    }

    private fun cerrar(n: Nodo) {
        n.hijos.sortWith(compareBy<Nodo>({ !it.esDir }, { it.nombre.lowercase() }))
        if (!n.esDir) {
            n.tamano = n.entrada?.tamano?.coerceAtLeast(0L) ?: 0L
            return
        }
        val hojas = ArrayList<Nodo>()
        var t = 0L
        for (h in n.hijos) {
            cerrar(h)
            t += h.tamano
            if (h.esDir) hojas.addAll(h.archivos) else hojas.add(h)
        }
        n.tamano = t
        n.archivos = hojas
    }
}

class ContenedorViewModel : ViewModel() {
    var contenedor: Contenedor? = null
        private set
    var raiz: Nodo? = null
        private set
    val expandidos = HashSet<String>()
    val seleccion = HashSet<String>()
    var consulta: String = ""
    var filtro: Iconos.Categoria? = null
    var cargando: Boolean = false
        private set
    var error: String? = null
    val cambiosApk = CambiosApk()
    val revision = MutableStateFlow(0)

    fun notificar() {
        revision.value = revision.value + 1
    }

    fun abrir(ctx: Context, uri: Uri) {
        val app = ctx.applicationContext
        viewModelScope.launch {
            cargando = true
            error = null
            notificar()
            try {
                val c = CatalogoMotores.abrir(app, uri)
                val arbol = withContext(Dispatchers.Default) { ArbolBuilder.construir(c.entradas) }
                cerrarInterno()
                contenedor = c
                raiz = arbol
                PreferenciasApp(app).sumar("stat_abiertos", 1L)
            } catch (t: Throwable) {
                error = Ui.mensajeError(t)
            } finally {
                cargando = false
                notificar()
            }
        }
    }

    private fun cerrarInterno() {
        contenedor?.close()
        contenedor?.archivo?.delete()
        contenedor = null
        raiz = null
        expandidos.clear()
        seleccion.clear()
        consulta = ""
        filtro = null
        cambiosApk.limpiar()
    }

    fun cerrar() {
        cerrarInterno()
        error = null
        notificar()
    }

    fun visibles(): List<Nodo> {
        val r = raiz ?: return emptyList()
        val q = consulta.trim().lowercase()
        val f = filtro
        val salida = ArrayList<Nodo>()
        if (q.isEmpty() && f == null) {
            fun visitar(n: Nodo) {
                for (h in n.hijos) {
                    salida.add(h)
                    if (h.esDir && h.ruta in expandidos) visitar(h)
                }
            }
            visitar(r)
        } else {
            for (h in r.archivos) {
                if (f != null && h.entrada?.categoria != f) continue
                if (q.isNotEmpty() && !h.ruta.lowercase().contains(q)) continue
                h.nombreMostrado = h.ruta
                salida.add(h)
            }
        }
        return salida
    }

    /** 0 = ninguno, 1 = parcial, 2 = todos */
    fun estadoSeleccion(n: Nodo): Int {
        if (!n.esDir) return if (n.entrada != null && n.entrada.ruta in seleccion) 2 else 0
        val hojas = n.archivos
        if (hojas.isEmpty()) return 0
        var marcadas = 0
        for (h in hojas) if (h.entrada != null && h.entrada.ruta in seleccion) marcadas++
        return when (marcadas) {
            0 -> 0
            hojas.size -> 2
            else -> 1
        }
    }

    fun alternar(n: Nodo) {
        if (!n.esDir) {
            val e = n.entrada ?: return
            if (!seleccion.remove(e.ruta)) seleccion.add(e.ruta)
        } else {
            val todos = estadoSeleccion(n) == 2
            for (h in n.archivos) {
                val e = h.entrada ?: continue
                if (todos) seleccion.remove(e.ruta) else seleccion.add(e.ruta)
            }
        }
        notificar()
    }

    fun marcarTodo() {
        val r = raiz ?: return
        for (h in r.archivos) h.entrada?.let { seleccion.add(it.ruta) }
        notificar()
    }

    fun desmarcarTodo() {
        seleccion.clear()
        notificar()
    }

    fun alternarExpandido(n: Nodo) {
        if (!expandidos.remove(n.ruta)) expandidos.add(n.ruta)
        notificar()
    }

    fun totalArchivos(): Int = raiz?.archivos?.size ?: 0

    override fun onCleared() {
        cerrarInterno()
        super.onCleared()
    }
}
