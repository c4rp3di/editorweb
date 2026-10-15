package com.carpe.panoptes.ui.modules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carpe.panoptes.PanoptesApp
import com.carpe.panoptes.modules.ModuleCatalog
import com.carpe.panoptes.modules.ModuleCatalogRepository
import com.carpe.panoptes.modules.ModuleDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModulesUiState(
    val modulos: List<ModuleCatalog.Entry> = emptyList(),
    val instalados: Set<String> = emptySet(),
    val progreso: Map<String, ModuleDownloader.Progress> = emptyMap(),
    val cargando: Boolean = true,
    val mensaje: String? = null
)

class ModulesViewModel(private val app: PanoptesApp) : ViewModel() {

    private val repo = ModuleCatalogRepository(app)
    private val downloader = ModuleDownloader(app)

    private val _state = MutableStateFlow(ModulesUiState())
    val state: StateFlow<ModulesUiState> = _state.asStateFlow()

    init {
        cargarCatalogo()
    }

    fun cargarCatalogo() {
        viewModelScope.launch {
            _state.value = _state.value.copy(cargando = true)
            val catalogo = repo.load()
            _state.value = _state.value.copy(
                modulos = catalogo.modules,
                instalados = app.moduleManager.listInstalled().map { it.id }.toSet(),
                cargando = false
            )
        }
    }

    fun instalar(entrada: ModuleCatalog.Entry) {
        if (!entrada.publicado) {
            _state.value = _state.value.copy(mensaje = "Este módulo aún no está publicado en el catálogo")
            return
        }
        viewModelScope.launch {
            try {
                downloader.downloadAndInstall(entrada) { progreso ->
                    _state.value = _state.value.copy(
                        progreso = _state.value.progreso + (entrada.id to progreso)
                    )
                }
                app.moduleManager.scanInstalled()
                _state.value = _state.value.copy(
                    instalados = app.moduleManager.listInstalled().map { it.id }.toSet(),
                    mensaje = "${entrada.name} instalado"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(mensaje = "Error instalando ${entrada.id}: ${e.message}")
            }
        }
    }

    fun desinstalar(id: String) {
        app.moduleManager.uninstall(id)
        _state.value = _state.value.copy(
            instalados = app.moduleManager.listInstalled().map { it.id }.toSet()
        )
    }

    fun mensajeMostrado() {
        _state.value = _state.value.copy(mensaje = null)
    }
}
