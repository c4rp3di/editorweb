package com.luzcontrol.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.luzcontrol.app.domain.model.*
import com.luzcontrol.app.domain.repository.*
import com.luzcontrol.app.domain.usecase.ObserveActiveCupsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ValidationState {
    data object Idle : ValidationState
    data object Validating : ValidationState
    data class Ok(val supplies: Int) : ValidationState
    data class Failed(val message: String) : ValidationState
}

sealed interface SettingsEvent {
    data class Share(val file: ExportedFile) : SettingsEvent
    data class Message(val text: String) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val credentials: CredentialsRepository,
    private val validator: CredentialsValidator,
    private val energy: EnergyRepository,
    private val exporter: ExportRepository,
    private val scheduler: SyncScheduler,
    observeActiveCups: ObserveActiveCupsUseCase
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepo.settings
    val hasCredentials: StateFlow<Boolean> = credentials.hasDatadisCredentials

    val supplies: StateFlow<List<Supply>> = energy.observeSupplies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeCups: StateFlow<String?> = observeActiveCups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _validation = MutableStateFlow<ValidationState>(ValidationState.Idle)
    val validation: StateFlow<ValidationState> = _validation

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    val hasEsiosToken: Boolean get() = credentials.getEsiosToken() != null
    val savedUsername: String get() = credentials.getDatadis()?.username.orEmpty()

    /** Valida contra Datadis y, si es correcto, guarda cifrado y lanza una sincronización. */
    fun validateAndSave(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _validation.value = ValidationState.Failed("Introduce DNI/NIE y contraseña de Datadis.")
            return
        }
        _validation.value = ValidationState.Validating
        viewModelScope.launch {
            val creds = DatadisCredentials(username.trim(), password)
            validator.validate(creds).fold(
                onSuccess = { n ->
                    credentials.saveDatadis(creds)
                    _validation.value = ValidationState.Ok(n)
                    scheduler.syncNow()
                },
                onFailure = { e ->
                    val msg = (e as? AppError)?.userMessage() ?: "No se pudieron validar las credenciales."
                    _validation.value = ValidationState.Failed(msg)
                }
            )
        }
    }

    fun clearCredentials() {
        credentials.clearDatadis()
        _validation.value = ValidationState.Idle
    }

    fun saveEsiosToken(token: String) {
        credentials.saveEsiosToken(token)
        _events.tryEmit(SettingsEvent.Message(if (token.isBlank()) "Token de ESIOS eliminado" else "Token de ESIOS guardado"))
    }

    fun setActiveCups(cups: String) = settingsRepo.update { it.copy(activeCups = cups) }
    fun setAlias(cups: String, alias: String) { viewModelScope.launch { energy.setSupplyAlias(cups, alias) } }
    fun setDailySync(v: Boolean) = settingsRepo.update { it.copy(dailySync = v) }
    fun setPriceNotifications(v: Boolean) = settingsRepo.update { it.copy(priceNotifications = v) }
    fun setRequireBiometric(v: Boolean) = settingsRepo.update { it.copy(requireBiometric = v) }
    fun setApiV2(v: Boolean) = settingsRepo.update { it.copy(datadisApiV2 = v) }
    fun setBilling(b: BillingParams) = settingsRepo.update { it.copy(billing = b) }
    fun resetBilling() = settingsRepo.update { it.copy(billing = BillingParams()) }

    fun export(pdf: Boolean) {
        val cups = activeCups.value
        if (cups == null) {
            _events.tryEmit(SettingsEvent.Message("No hay datos que exportar todavía."))
            return
        }
        _exporting.value = true
        viewModelScope.launch {
            val result = if (pdf) exporter.exportPdf(cups) else exporter.exportCsv(cups)
            _exporting.value = false
            result.fold(
                onSuccess = { _events.tryEmit(SettingsEvent.Share(it)) },
                onFailure = { _events.tryEmit(SettingsEvent.Message("No se pudo exportar: ${it.message}")) }
            )
        }
    }

    fun clearLocalData() {
        viewModelScope.launch {
            energy.clearAllData()
            settingsRepo.update { it.copy(activeCups = null) }
            _events.tryEmit(SettingsEvent.Message("Datos locales borrados"))
        }
    }
}
