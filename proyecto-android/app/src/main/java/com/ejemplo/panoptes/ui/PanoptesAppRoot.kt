package com.carpe.panoptes.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.carpe.panoptes.PanoptesApp
import com.carpe.panoptes.ui.nav.PanoptesNavHost
import com.carpe.panoptes.ui.settings.LegalDialog
import com.carpe.panoptes.ui.theme.PanoptesTheme
import kotlinx.coroutines.launch

@Composable
fun PanoptesAppRoot() {
    val app = LocalContext.current.applicationContext as PanoptesApp
    val scope = rememberCoroutineScope()
    val legalAceptado by app.settingsRepository.legalAceptado.collectAsState(initial = null)

    PanoptesTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // null = todavía no hemos leído la preferencia; no mostramos nada
            // (ni la app ni el diálogo) para no parpadear el diálogo en cada arranque.
            when (legalAceptado) {
                null -> { /* cargando preferencia */ }
                false -> LegalDialog(onAceptar = { scope.launch { app.settingsRepository.marcarLegalAceptado() } })
                true -> PanoptesNavHost()
            }
        }
    }
}
