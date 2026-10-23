package com.luzcontrol.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.luzcontrol.app.domain.model.SyncLog
import com.luzcontrol.app.domain.repository.EnergyRepository
import com.luzcontrol.app.domain.repository.SettingsRepository
import com.luzcontrol.app.domain.repository.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val scheduler: SyncScheduler,
    settings: SettingsRepository,
    energy: EnergyRepository
) : ViewModel() {

    val syncing: StateFlow<Boolean> = scheduler.observeSyncing()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val lastSync: StateFlow<SyncLog?> = energy.observeLastSync()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ajustes se desbloquea con biometría y se vuelve a bloquear al salir de la pestaña. */
    var settingsUnlocked: Boolean = false

    val biometricRequired: StateFlow<Boolean> = settings.settings
        .map { it.requireBiometric }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.requireBiometric)

    init {
        viewModelScope.launch {
            settings.settings.map { it.dailySync }.distinctUntilChanged().collect { enabled ->
                scheduler.setPeriodicEnabled(enabled)
            }
        }
        // Al abrir la app: si la última sincronización tiene más de 6 h, se lanza una en segundo plano.
        scheduler.syncOnOpenIfStale(6)
    }

    fun syncNow() = scheduler.syncNow()
}
