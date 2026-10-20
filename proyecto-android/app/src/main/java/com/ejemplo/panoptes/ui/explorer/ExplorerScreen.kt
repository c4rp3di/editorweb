package com.carpe.panoptes.ui.explorer

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.carpe.panoptes.core.files.AnalysisFiles
import com.carpe.panoptes.ui.panoptesViewModel
import java.io.File

private fun tamanoLegible(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    analysisId: String,
    onBack: () -> Unit,
    viewModel: ExplorerViewModel = panoptesViewModel { ExplorerViewModel(it, analysisId) }
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.mensaje) {
        state.mensaje?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.mensajeMostrado()
        }
    }
    LaunchedEffect(state.zipListo) {
        state.zipListo?.let { zip ->
            compartirZip(context, zip)
            viewModel.zipCompartido()
        }
    }

    BackHandler(enabled = state.abierto != null || state.resultados != null || state.ruta.isNotEmpty()) {
        viewModel.subir()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.abierto?.ruta ?: ("Archivos" + if (state.ruta.isEmpty()) "" else "/" + state.ruta),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.subir()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (state.abierto == null) {
                        IconButton(onClick = { viewModel.alternarBusqueda() }) {
                            Icon(Icons.Filled.Search, contentDescription = "Buscar en el código")
                        }
                    }
                    IconButton(onClick = { viewModel.exportar() }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "Exportar como ZIP")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val abierto = state.abierto
            if (abierto != null) {
                VisorArchivo(abierto)
                return@Column
            }

            if (state.busquedaActiva) {
                OutlinedTextField(
                    value = state.consulta,
                    onValueChange = viewModel::cambiarConsulta,
                    singleLine = true,
                    label = { Text("Buscar en ${if (state.ruta.isEmpty()) "todos los archivos" else state.ruta}") },
                    trailingIcon = {
                        IconButton(onClick = { viewModel.buscar() }) { Icon(Icons.Filled.Search, contentDescription = "Buscar") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (state.buscando) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            val resultados = state.resultados
            if (resultados != null) {
                Text(
                    "${resultados.coincidencias.size} coincidencia(s) en ${resultados.archivosRevisados} archivo(s)" +
                        if (resultados.truncado) " — resultados limitados, afina la búsqueda" else "",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(resultados.coincidencias) { c ->
                        ListItem(
                            modifier = Modifier.clickable { viewModel.abrirArchivo(c.ruta, c.linea) },
                            headlineContent = { Text(c.texto, maxLines = 2, fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                            supportingContent = { Text("${c.ruta}:${c.linea}", style = MaterialTheme.typography.labelSmall) }
                        )
                        HorizontalDivider()
                    }
                }
                return@Column
            }

            if (state.entradas.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay archivos aquí todavía.\nInstala los módulos jadx/apktool y vuelve a analizar.")
                }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.entradas, key = { it.ruta }) { e ->
                    ListItem(
                        modifier = Modifier.clickable {
                            if (e.esDir) viewModel.abrirCarpeta(e.ruta) else viewModel.abrirArchivo(e.ruta)
                        },
                        leadingContent = {
                            Icon(if (e.esDir) Icons.Filled.Folder else Icons.Filled.Description, contentDescription = null)
                        },
                        headlineContent = { Text(e.nombre) },
                        supportingContent = { if (!e.esDir) Text(tamanoLegible(e.tamano)) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun VisorArchivo(abierto: ArchivoAbierto) {
    when (val c = abierto.contenido) {
        is AnalysisFiles.Contenido.Binario -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Archivo binario (${tamanoLegible(c.tamano)}): no se puede mostrar como texto.\nUsa Exportar como ZIP para sacarlo de la app.")
            }
        }
        is AnalysisFiles.Contenido.Texto -> {
            val lineas = remember(c.texto) { c.texto.lines() }
            val listState = rememberLazyListState()
            LaunchedEffect(abierto) {
                listState.scrollToItem((abierto.linea - 1).coerceIn(0, (lineas.size - 1).coerceAtLeast(0)))
            }
            if (c.truncado) {
                Text(
                    "Mostrando los primeros ${tamanoLegible(c.texto.length.toLong())} de ${tamanoLegible(c.tamano)}.",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState) {
                    itemsIndexed(lineas) { i, linea ->
                        val resaltada = (i + 1) == abierto.linea
                        Text(
                            "${(i + 1).toString().padStart(5)}  $linea",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (resaltada) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

private fun compartirZip(context: android.content.Context, zip: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Exportar archivos del análisis"))
}
