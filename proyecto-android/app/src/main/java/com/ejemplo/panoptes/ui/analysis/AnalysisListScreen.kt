package com.carpe.panoptes.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carpe.panoptes.R
import com.carpe.panoptes.analyzer.orchestrator.AnalysisProgressBus
import com.carpe.panoptes.core.db.AnalysisEntity
import com.carpe.panoptes.ui.panoptesViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisListScreen(
    onBack: () -> Unit,
    onAbrir: (String) -> Unit,
    viewModel: AnalysisListViewModel = panoptesViewModel { AnalysisListViewModel(it) }
) {
    val lista by viewModel.analisis.collectAsStateWithLifecycle()
    val progresos by AnalysisProgressBus.estado.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ver_analisis)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        if (lista.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.sin_analisis_todavia))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(lista, key = { it.id }) { item -> AnalysisRow(item, progresos[item.id]) { onAbrir(item.id) } }
        }
    }
}

@Composable
private fun AnalysisRow(item: AnalysisEntity, progreso: AnalysisProgressBus.Estado?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text("${item.packageName} (${item.versionName ?: "?"})") },
        supportingContent = {
            Column {
                Text("${item.status} — ${DateFormat.getDateTimeInstance().format(Date(item.startedAt))}")
                ProgresoAnalisis(item, progreso)
            }
        }
    )
    HorizontalDivider()
}
