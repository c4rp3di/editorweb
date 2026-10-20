package com.carpe.panoptes.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carpe.panoptes.PanoptesApp
import com.carpe.panoptes.core.db.AnalysisEntity
import com.carpe.panoptes.core.db.ComponentEntity
import com.carpe.panoptes.core.db.EndpointEntity
import com.carpe.panoptes.analyzer.orchestrator.AnalysisOrchestrator
import com.carpe.panoptes.core.db.FindingEntity
import com.carpe.panoptes.core.db.NativeLibEntity
import com.carpe.panoptes.core.db.PermissionEntity
import com.carpe.panoptes.core.db.RuntimeEventEntity
import com.carpe.panoptes.core.db.SecretEntity
import com.carpe.panoptes.dynamic.RootChecker
import com.carpe.panoptes.report.ReportFormat
import com.carpe.panoptes.report.ReportGenerator
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AnalysisDetailUiState(
    val analisis: AnalysisEntity? = null,
    val findings: List<FindingEntity> = emptyList(),
    val permisos: List<PermissionEntity> = emptyList(),
    val componentes: List<ComponentEntity> = emptyList(),
    val endpoints: List<EndpointEntity> = emptyList(),
    val secretos: List<SecretEntity> = emptyList(),
    val nativeLibs: List<NativeLibEntity> = emptyList(),
    val runtimeEvents: List<RuntimeEventEntity> = emptyList(),
    val fridaDisponible: Boolean = false,
    val rooteado: Boolean = false,
    val ejecutandoDinamico: Boolean = false,
    val mensajeDinamico: String? = null,
    val cargando: Boolean = true,
    val reporteListo: File? = null
)

class AnalysisDetailViewModel(private val app: PanoptesApp) : ViewModel() {

    private val _state = MutableStateFlow(AnalysisDetailUiState())
    val state: StateFlow<AnalysisDetailUiState> = _state.asStateFlow()

    fun cargar(analysisId: String) {
        viewModelScope.launch {
            val db = app.database
            _state.value = AnalysisDetailUiState(
                analisis = db.analysisDao().getById(analysisId),
                findings = db.resultsDao().findingsFor(analysisId),
                permisos = db.resultsDao().permissionsFor(analysisId),
                componentes = db.resultsDao().componentsFor(analysisId),
                endpoints = db.resultsDao().endpointsFor(analysisId),
                secretos = db.resultsDao().secretsFor(analysisId),
                nativeLibs = db.resultsDao().nativeLibsFor(analysisId),
                runtimeEvents = db.resultsDao().runtimeEventsFor(analysisId),
                fridaDisponible = app.moduleManager.isInstalled("frida-server"),
                rooteado = RootChecker.pareceRooteado(),
                cargando = false
            )
        }
    }

    /** F6: análisis dinámico explícito — nunca automático (ver AnalysisOrchestrator). */
    fun ejecutarAnalisisDinamico(analysisId: String) {
        val analisis = _state.value.analisis ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(ejecutandoDinamico = true, mensajeDinamico = null)
            try {
                val orquestador = AnalysisOrchestrator(app, app.database, app.moduleManager)
                val resultado = orquestador.runDynamicAnalysis(analysisId, analisis.apkPath)
                _state.value = _state.value.copy(
                    mensajeDinamico = when {
                        resultado == null -> "No se pudo ejecutar (¿frida-server instalado? ¿root?)"
                        !resultado.success -> "Falló: ${resultado.error}"
                        else -> "Completado — revisa la sección de eventos runtime abajo"
                    }
                )
                cargar(analysisId) // recarga para mostrar los runtime_event nuevos
            } catch (e: Exception) {
                _state.value = _state.value.copy(mensajeDinamico = "Error: ${e.message}")
            } finally {
                _state.value = _state.value.copy(ejecutandoDinamico = false)
            }
        }
    }

    fun mensajeDinamicoMostrado() {
        _state.value = _state.value.copy(mensajeDinamico = null)
    }

    /** F7: genera el informe en el formato pedido; AnalysisDetailScreen abre el share sheet cuando reporteListo cambia. */
    fun exportarInforme(analysisId: String, formato: ReportFormat) {
        viewModelScope.launch {
            try {
                val archivo = ReportGenerator(app, app.database).generar(analysisId, formato)
                _state.value = _state.value.copy(reporteListo = archivo)
            } catch (e: Exception) {
                _state.value = _state.value.copy(mensajeDinamico = "No se pudo generar el informe: ${e.message}")
            }
        }
    }

    fun reporteCompartido() {
        _state.value = _state.value.copy(reporteListo = null)
    }
}
