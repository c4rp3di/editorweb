package com.carpe.panoptes.ui.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carpe.panoptes.PanoptesApp
import com.carpe.panoptes.core.files.AnalysisFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ArchivoAbierto(val ruta: String, val contenido: AnalysisFiles.Contenido, val linea: Int)

data class ExplorerUiState(
    val ruta: String = "",
    val entradas: List<AnalysisFiles.Entrada> = emptyList(),
    val abierto: ArchivoAbierto? = null,
    val busquedaActiva: Boolean = false,
    val consulta: String = "",
    val buscando: Boolean = false,
    val resultados: AnalysisFiles.ResultadoBusqueda? = null,
    val zipListo: File? = null,
    val mensaje: String? = null
)

/** Explorador de solo lectura de lo que ha generado el análisis (código descompilado, recursos, DEX). */
class ExplorerViewModel(
    private val app: PanoptesApp,
    private val analysisId: String,
    private val consultaInicial: String = "",
    private val archivoInicial: String = ""
) : ViewModel() {

    private val raiz = File(app.filesDir, "analysis/$analysisId")

    private val _state = MutableStateFlow(ExplorerUiState())
    val state: StateFlow<ExplorerUiState> = _state.asStateFlow()

    private var busquedaJob: Job? = null

    init {
        abrirCarpeta("")
        if (archivoInicial.isNotEmpty()) {
            abrirArchivo(archivoInicial, 1)
        } else if (consultaInicial.isNotEmpty()) {
            _state.value = _state.value.copy(busquedaActiva = true, consulta = consultaInicial)
            buscar()
        }
    }

    fun abrirCarpeta(ruta: String) {
        viewModelScope.launch {
            val entradas = withContext(Dispatchers.IO) { AnalysisFiles.listar(raiz, ruta) }
            _state.value = _state.value.copy(ruta = ruta, entradas = entradas, abierto = null)
        }
    }

    /** Sube un nivel; false si ya estaba en la raíz. */
    fun subir(): Boolean {
        val s = _state.value
        if (s.abierto != null) { cerrarArchivo(); return true }
        if (s.resultados != null) { limpiarBusqueda(); return true }
        if (s.ruta.isEmpty()) return false
        abrirCarpeta(s.ruta.substringBeforeLast('/', ""))
        return true
    }

    fun abrirArchivo(ruta: String, linea: Int = 1) {
        viewModelScope.launch {
            val contenido = withContext(Dispatchers.IO) { AnalysisFiles.leer(raiz, ruta) }
            if (contenido == null) {
                _state.value = _state.value.copy(mensaje = "No se pudo abrir $ruta")
            } else {
                _state.value = _state.value.copy(abierto = ArchivoAbierto(ruta, contenido, linea))
            }
        }
    }

    fun cerrarArchivo() {
        _state.value = _state.value.copy(abierto = null)
    }

    fun alternarBusqueda() {
        val activa = !_state.value.busquedaActiva
        if (!activa) limpiarBusqueda()
        _state.value = _state.value.copy(busquedaActiva = activa)
    }

    fun cambiarConsulta(texto: String) {
        _state.value = _state.value.copy(consulta = texto)
    }

    fun buscar() {
        val consulta = _state.value.consulta.trim()
        if (consulta.length < 2) {
            _state.value = _state.value.copy(mensaje = "Escribe al menos 2 caracteres")
            return
        }
        busquedaJob?.cancel()
        val carpeta = _state.value.ruta
        busquedaJob = viewModelScope.launch {
            _state.value = _state.value.copy(buscando = true, resultados = null)
            val resultado = withContext(Dispatchers.IO) {
                val job = coroutineContext[Job]
                AnalysisFiles.buscar(raiz, carpeta, consulta, cancelado = { job?.isActive == false })
            }
            _state.value = _state.value.copy(buscando = false, resultados = resultado)
        }
    }

    private fun limpiarBusqueda() {
        busquedaJob?.cancel()
        _state.value = _state.value.copy(buscando = false, resultados = null)
    }

    /** Comprime la carpeta actual (o el fichero abierto) en un ZIP listo para compartir. */
    fun exportar() {
        val s = _state.value
        val ruta = s.abierto?.ruta ?: s.ruta
        viewModelScope.launch {
            try {
                val destino = withContext(Dispatchers.IO) {
                    val nombre = (if (ruta.isEmpty()) "analisis" else ruta.substringAfterLast('/')) + "-" + analysisId.take(8) + ".zip"
                    val zip = File(app.cacheDir, "reports/$nombre")
                    val n = AnalysisFiles.comprimir(raiz, ruta, zip)
                    if (n == 0) throw IllegalStateException("No hay ficheros que exportar aquí")
                    zip
                }
                _state.value = _state.value.copy(zipListo = destino)
            } catch (e: Exception) {
                _state.value = _state.value.copy(mensaje = "No se pudo exportar: ${e.message}")
            }
        }
    }

    fun zipCompartido() {
        _state.value = _state.value.copy(zipListo = null)
    }

    fun mensajeMostrado() {
        _state.value = _state.value.copy(mensaje = null)
    }
}
