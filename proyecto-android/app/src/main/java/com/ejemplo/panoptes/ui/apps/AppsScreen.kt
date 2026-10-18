package com.carpe.panoptes.ui.apps

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carpe.panoptes.R
import com.carpe.panoptes.ingest.ApkExtractor
import com.carpe.panoptes.ui.panoptesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    onVerAnalisis: () -> Unit,
    onVerModulos: () -> Unit,
    onAjustes: () -> Unit,
    viewModel: AppsViewModel = panoptesViewModel { AppsViewModel(it) }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.ultimoMensaje) {
        state.ultimoMensaje?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.mensajeMostrado()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_titulo)) },
                actions = {
                    IconButton(onClick = onVerAnalisis) {
                        Icon(Icons.Filled.History, contentDescription = stringResource(R.string.ver_analisis))
                    }
                    IconButton(onClick = onVerModulos) {
                        Icon(Icons.Filled.Extension, contentDescription = stringResource(R.string.modulos_titulo))
                    }
                    IconButton(onClick = onAjustes) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.ajustes_titulo))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (state.cargando) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(state.apps, key = { it.paquete }) { app ->
                AppRow(app) { viewModel.extraerYAnalizar(app.info) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AppRow(app: ApkExtractor.InstalledApp, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { AppIcon(app.icono) },
        headlineContent = { Text(app.nombre, maxLines = 1) },
        supportingContent = { Text(app.paquete, maxLines = 1) },
        trailingContent = { Text(stringResource(R.string.toca_para_analizar), style = MaterialTheme.typography.labelSmall) }
    )
}

@Composable
private fun AppIcon(drawable: Drawable) {
    val bitmap = remember(drawable) { drawable.toBitmap().asImageBitmap() }
    Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
}
