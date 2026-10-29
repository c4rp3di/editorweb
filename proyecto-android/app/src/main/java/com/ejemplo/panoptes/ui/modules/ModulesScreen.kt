package com.carpe.panoptes.ui.modules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carpe.panoptes.R
import com.carpe.panoptes.modules.ModuleCatalog
import com.carpe.panoptes.modules.ModuleDownloader
import com.carpe.panoptes.ui.panoptesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(
    onBack: () -> Unit,
    onAjustes: () -> Unit = {},
    viewModel: ModulesViewModel = panoptesViewModel { ModulesViewModel(it) }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.mensaje) {
        state.mensaje?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.mensajeMostrado()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.modulos_titulo)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
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

        if (state.modulos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Catálogo vacío todavía. Publica un módulo vía CI " +
                        "(build-module-jadx.yml → publish-catalog.yml) y vuelve a entrar aquí.",
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(state.modulos, key = { it.id }) { modulo ->
                ModuleRow(
                    entrada = modulo,
                    instalado = modulo.id in state.instalados,
                    progreso = state.progreso[modulo.id],
                    onInstalar = { viewModel.instalar(modulo) },
                    onDesinstalar = { viewModel.desinstalar(modulo.id) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ModuleRow(
    entrada: ModuleCatalog.Entry,
    instalado: Boolean,
    progreso: ModuleDownloader.Progress?,
    onInstalar: () -> Unit,
    onDesinstalar: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(entrada.name, fontWeight = FontWeight.Bold)
        Text(
            buildString {
                append(entrada.description)
                append(" · v${entrada.version} · ${entrada.sizeBytes / (1024 * 1024)}MB")
                if (entrada.requiresRoot) append(" · requiere root")
                if (!entrada.publicado) append(" · [no publicado todavía]")
            },
            style = MaterialTheme.typography.bodySmall
        )

        if (progreso is ModuleDownloader.Progress.Downloading && progreso.totalBytes > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progreso.bytesRead.toFloat() / progreso.totalBytes.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (instalado) {
                OutlinedButton(onClick = onDesinstalar) { Text(stringResource(R.string.modulo_desinstalar)) }
            } else {
                Button(onClick = onInstalar, enabled = entrada.publicado) {
                    Text(stringResource(R.string.modulo_instalar))
                }
            }
        }
    }
}
