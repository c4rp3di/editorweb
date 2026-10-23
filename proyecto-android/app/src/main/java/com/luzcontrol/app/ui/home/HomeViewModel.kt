package com.luzcontrol.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.luzcontrol.app.domain.model.DashboardData
import com.luzcontrol.app.domain.usecase.GetDashboardUseCase
import com.luzcontrol.app.domain.usecase.ObserveActiveCupsUseCase
import com.luzcontrol.app.ui.components.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeActiveCups: ObserveActiveCupsUseCase,
    getDashboard: GetDashboardUseCase
) : ViewModel() {

    private val emptyMessage = "Aún no hay datos. Configura tus credenciales de Datadis o espera a la primera sincronización."

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<DashboardData>> = observeActiveCups()
        .flatMapLatest { cups ->
            if (cups == null) flowOf<UiState<DashboardData>>(UiState.Empty(emptyMessage))
            else getDashboard(cups).map { toState(it) }
        }
        .catch { emit(UiState.Error("No se pudieron leer los datos guardados.")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    private fun toState(d: DashboardData): UiState<DashboardData> = when {
        d.dayKwh == null -> UiState.Empty(emptyMessage)
        d.stale -> UiState.Stale(d)
        else -> UiState.Success(d)
    }
}
