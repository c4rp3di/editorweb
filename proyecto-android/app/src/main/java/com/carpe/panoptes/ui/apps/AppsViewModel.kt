package com.carpe.panoptes.ui.apps

import android.content.pm.PackageInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.carpe.panoptes.PanoptesApp
import com.carpe.panoptes.ingest.ApkExtractor
import com.carpe.panoptes.work.AnalysisWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppsUiState(
    val apps: List<ApkExtractor.InstalledApp> = emptyList(),
    val cargando: Boolean = true,
    val ultimoMensaje: String? = null
)

class AppsViewModel(private val app: PanoptesApp) : ViewModel() {

    private val extractor = ApkExtractor(app)
    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()

    init {
        cargarApps()
    }

    fun cargarApps() {
        viewModelScope.launch {
            _state.value = _state.value.copy(cargando = true)
            val lista = extractor.listarAppsInstaladas()
            _state.value = _state.value.copy(apps = lista, cargando = false)
        }
    }

    fun extraerYAnalizar(info: PackageInfo) {
        viewModelScope.launch {
            try {
                val resultado = extractor.extraer(info)
                if (resultado.uris.isEmpty()) {
                    _state.value = _state.value.copy(ultimoMensaje = "No se pudo extraer el APK")
                    return@launch
                }
                // H-06: base + splits. El hash guardado en el análisis es el del APK base
                // (resultado.sha256Base); el orquestador registra el conjunto completo.
                val apkPath = resultado.archivos.first().absolutePath
                val extras = resultado.archivos.drop(1).map { it.absolutePath }
                val entidad = app.analysisRepository.registrarExtraccion(
                    packageName = info.packageName,
                    versionName = info.versionName,
                    apkPath = apkPath,
                    sha256 = resultado.sha256Base
                )

                val datos = Data.Builder()
                    .putString(AnalysisWorker.KEY_ANALYSIS_ID, entidad.id)
                    .putString(AnalysisWorker.KEY_APK_PATH, apkPath)
                    .putStringArray(AnalysisWorker.KEY_EXTRA_APKS, extras.toTypedArray())
                    .build()
                val request = OneTimeWorkRequestBuilder<AnalysisWorker>().setInputData(datos).build()
                WorkManager.getInstance(app).enqueue(request)

                _state.value = _state.value.copy(
                    ultimoMensaje = "Análisis iniciado: ${info.packageName}"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(ultimoMensaje = "Error: ${e.message}")
            }
        }
    }

    fun mensajeMostrado() {
        _state.value = _state.value.copy(ultimoMensaje = null)
    }
}
