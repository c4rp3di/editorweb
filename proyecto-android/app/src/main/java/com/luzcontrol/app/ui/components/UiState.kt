package com.luzcontrol.app.ui.components

/** Estados explícitos de cada pantalla (Loading / Success / Stale / Empty / Error). */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    /** Datos válidos pero de hace más de 48 h. */
    data class Stale<T>(val data: T) : UiState<T>
    data class Empty(val message: String) : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
}
