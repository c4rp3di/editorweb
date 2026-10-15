package com.carpe.panoptes.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import com.carpe.panoptes.PanoptesApp

/**
 * Sin Hilt/Dagger a propósito: la app tiene un puñado de pantallas y todas
 * las dependencias ya viven en PanoptesApp (Application). Añadir DI aquí
 * sería complejidad sin beneficio real a este tamaño — si el proyecto
 * crece mucho más, este es el sitio a revisar primero.
 */
@Composable
inline fun <reified T : ViewModel> panoptesViewModel(crossinline create: (PanoptesApp) -> T): T {
    val app = LocalContext.current.applicationContext as PanoptesApp
    return viewModel(
        factory = viewModelFactory {
            initializer { create(app) }
        }
    )
}
