package com.carpe.panoptes.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carpe.panoptes.PanoptesApp
import com.carpe.panoptes.core.db.AnalysisEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AnalysisListViewModel(app: PanoptesApp) : ViewModel() {
    val analisis: StateFlow<List<AnalysisEntity>> = app.analysisRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
