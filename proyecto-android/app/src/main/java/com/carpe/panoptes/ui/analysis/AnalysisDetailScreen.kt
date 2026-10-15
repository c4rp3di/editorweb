package com.carpe.panoptes.ui.analysis

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carpe.panoptes.core.db.FindingEntity
import com.carpe.panoptes.core.db.Severity
import com.carpe.panoptes.report.ReportFormat
import com.carpe.panoptes.ui.panoptesViewModel
import java.io.File

/**
 * Pantalla de resultados, F3: ahora con secciones expandibles reales en vez
 * del bloque de texto monoespaciado de F1. El contenido es el mismo dato
 * (findings/permisos/componentes/endpoints/secretos) solo que mejor
 * presentado — esto es justo lo que la Fase 3 del plan pedía ("pulido de
 * UI" real llega en F7, esto es la base Compose sobre la que construirlo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisDetailScreen(
    analysisId: String,
    onBack: () -> Unit,
    viewModel: AnalysisDetailViewModel = panoptesViewModel { AnalysisDetailViewModel(it) }
) {
    LaunchedEffect(analysisId) { viewModel.cargar(analysisId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.mensajeDinamico) {
        state.mensajeDinamico?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.mensajeDinamicoMostrado()
        }
    }

    // F7: en cuanto el informe está escrito a disco, abrimos el share sheet.
    LaunchedEffect(state.reporteListo) {
        state.reporteListo?.let { archivo ->
            compartirArchivo(context, archivo)
            viewModel.reporteCompartido()
        }
    }

    var menuExportarAbierto by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.analisis?.packageName ?: "Análisis") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExportarAbierto = true }) {
                            Icon(Icons.Filled.FileDownload, contentDescription = "Exportar informe")
                        }
                        DropdownMenu(expanded = menuExportarAbierto, onDismissRequest = { menuExportarAbierto = false }) {
                            DropdownMenuItem(
                                text = { Text("Exportar como HTML") },
                                onClick = { menuExportarAbierto = false; viewModel.exportarInforme(analysisId, ReportFormat.HTML) }
                            )
                            DropdownMenuItem(
                                text = { Text("Exportar como JSON") },
                                onClick = { menuExportarAbierto = false; viewModel.exportarInforme(analysisId, ReportFormat.JSON) }
                            )
                            DropdownMenuItem(
                                text = { Text("Exportar como SARIF") },
                                onClick = { menuExportarAbierto = false; viewModel.exportarInforme(analysisId, ReportFormat.SARIF) }
                            )
                        }
                    }
                    val apkPath = state.analisis?.apkPath
                    if (apkPath != null) {
                        IconButton(onClick = { compartirApk(context, apkPath) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Compartir APK")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.cargando) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item { Spacer(Modifier.height(8.dp)) }

            item { SeccionTitulo("Hallazgos (${state.findings.size})") }
            items(state.findings.sortedByDescending { it.severity.ordinal }) { FindingRow(it) }

            item { SeccionTitulo("Permisos (${state.permisos.size})") }
            items(state.permisos) { p ->
                Text("${p.protectionLevel}: ${p.name}", style = MaterialTheme.typography.bodySmall)
            }

            item { SeccionTitulo("Componentes exportados (${state.componentes.count { it.exported }})") }
            items(state.componentes.filter { it.exported }) { c ->
                Text("${c.type}: ${c.name}", style = MaterialTheme.typography.bodySmall)
            }

            item { SeccionTitulo("Endpoints candidatos (${state.endpoints.size})") }
            items(state.endpoints) { e ->
                Text("(${"%.1f".format(e.confidence)}) ${e.url}", style = MaterialTheme.typography.bodySmall)
            }

            item { SeccionTitulo("Librerías nativas (${state.nativeLibs.size})") }
            items(state.nativeLibs) { lib ->
                Text("[${lib.arch}] ${lib.name}", style = MaterialTheme.typography.bodySmall)
            }

            item { SeccionAnalisisDinamico(state, onEjecutar = { viewModel.ejecutarAnalisisDinamico(analysisId) }) }

            if (state.runtimeEvents.isNotEmpty()) {
                item { SeccionTitulo("Eventos runtime (${state.runtimeEvents.size})") }
                items(state.runtimeEvents) { ev ->
                    Text("[${ev.type}] ${ev.payload}", style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                SeccionTitulo("Secretos candidatos (${state.secretos.size})")
                Text(
                    "Heurísticos, no confirmaciones — revísalos tú mismo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(state.secretos) { s ->
                Text(
                    "[${s.type}] ${enmascarar(s.value)} (entropía ${"%.1f".format(s.entropy)})",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * F6: botón de análisis dinámico. Siempre visible para que el usuario
 * entienda qué le falta (root, o instalar el módulo) en vez de
 * desaparecer sin más — consistente con el aviso del §10 del plan
 * ("Usuario sin root no puede usar Frida -> Banner explicativo").
 */
@Composable
private fun SeccionAnalisisDinamico(state: AnalysisDetailUiState, onEjecutar: () -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        SeccionTitulo("Análisis dinámico (F6)")
        when {
            !state.rooteado -> Text(
                "Necesita un dispositivo con root — no se detectó.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            !state.fridaDisponible -> Text(
                "Instala el módulo frida-server desde la pantalla de Módulos primero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> Button(onClick = onEjecutar, enabled = !state.ejecutandoDinamico) {
                if (state.ejecutandoDinamico) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.ejecutandoDinamico) "Ejecutando (~15s)…" else "Ejecutar análisis dinámico")
            }
        }
    }
}

@Composable
private fun SeccionTitulo(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun FindingRow(finding: FindingEntity) {
    val color = when (finding.severity) {
        Severity.HIGH -> MaterialTheme.colorScheme.error
        Severity.MEDIUM -> MaterialTheme.colorScheme.tertiary
        Severity.LOW, Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("[${finding.severity}]", color = color, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(6.dp))
        Text(finding.description, style = MaterialTheme.typography.bodySmall)
    }
}

/** No mostramos el secreto completo en pantalla por defecto. */
private fun enmascarar(valor: String): String =
    if (valor.length <= 8) "****" else valor.take(4) + "…" + valor.takeLast(4)

/** F7: comparte un informe generado (HTML/JSON/SARIF) vía el share sheet del sistema. */
private fun compartirArchivo(context: android.content.Context, archivo: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archivo)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = when (archivo.extension) {
            "json", "sarif" -> "application/json"
            "html" -> "text/html"
            else -> "application/octet-stream"
        }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartir informe"))
}

/** Recupera la funcionalidad original de F0: compartir/guardar el APK extraído. */
private fun compartirApk(context: android.content.Context, apkPath: String) {
    val file = File(apkPath)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartir / guardar APK"))
}
