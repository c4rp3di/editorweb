package com.carpe.panoptes.ui.settings

import androidx.compose.foundation.layout.*
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import com.carpe.panoptes.PanoptesApp
import com.carpe.panoptes.R
import com.carpe.panoptes.modules.CatalogSignature
import com.carpe.panoptes.modules.CatalogoConfig
import com.carpe.panoptes.modules.Config
import com.carpe.panoptes.ui.panoptesViewModel
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: PanoptesApp) : ViewModel() {
    fun guardar(c: CatalogoConfig, alTerminar: () -> Unit) {
        viewModelScope.launch {
            app.settingsRepository.guardarCatalogo(c)
            alTerminar()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = panoptesViewModel { SettingsViewModel(it) }
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as PanoptesApp
    val actual by app.settingsRepository.catalogo.collectAsStateWithLifecycle(initialValue = null)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val textoGuardado = stringResource(R.string.ajustes_guardado)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ajustes_titulo)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val cfg = actual
        if (cfg == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        // Se inicializa una sola vez con lo guardado; después manda lo que escribe el usuario.
        var owner by remember { mutableStateOf(cfg.owner) }
        var repo by remember { mutableStateOf(cfg.repo) }
        var clave by remember { mutableStateOf(cfg.clavePublicaB64) }
        var intentado by remember { mutableStateOf(false) }
        var privadaGenerada by remember { mutableStateOf<String?>(null) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val textoCopiado = stringResource(R.string.ajustes_copiado)

        privadaGenerada?.let { privada ->
            AlertDialog(
                onDismissRequest = { privadaGenerada = null },
                title = { Text(stringResource(R.string.ajustes_generar_titulo)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ajustes_generar_texto), style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(privada, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm?.setPrimaryClip(ClipData.newPlainText("CATALOG_SIGNING_KEY", privada))
                        scope.launch { snackbarHostState.showSnackbar(textoCopiado) }
                    }) { Text(stringResource(R.string.ajustes_copiar)) }
                },
                dismissButton = {
                    TextButton(onClick = { privadaGenerada = null }) { Text(stringResource(R.string.ajustes_cerrar)) }
                }
            )
        }

        val ownerOk = owner.isBlank() || Config.esOwnerValido(owner.trim())
        val repoOk = repo.isBlank() || Config.esRepoValido(repo.trim())
        val claveOk = clave.isBlank() || CatalogSignature.claveValida(clave)

        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.ajustes_catalogo_titulo), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ajustes_catalogo_ayuda), style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(
                value = owner, onValueChange = { owner = it }, singleLine = true,
                label = { Text(stringResource(R.string.ajustes_owner)) },
                isError = intentado && !ownerOk,
                supportingText = { if (intentado && !ownerOk) Text(stringResource(R.string.ajustes_owner_invalido)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = repo, onValueChange = { repo = it }, singleLine = true,
                label = { Text(stringResource(R.string.ajustes_repo)) },
                isError = intentado && !repoOk,
                supportingText = { if (intentado && !repoOk) Text(stringResource(R.string.ajustes_repo_invalido)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = clave, onValueChange = { clave = it }, minLines = 3,
                label = { Text(stringResource(R.string.ajustes_clave)) },
                isError = intentado && !claveOk,
                supportingText = { if (intentado && !claveOk) Text(stringResource(R.string.ajustes_clave_invalida)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    val (publica, privada) = CatalogSignature.generarPar()
                    clave = publica
                    privadaGenerada = privada
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.ajustes_generar)) }
            Button(
                onClick = {
                    intentado = true
                    if (ownerOk && repoOk && claveOk) {
                        viewModel.guardar(CatalogoConfig(owner.trim(), repo.trim(), clave)) {
                            scope.launch { snackbarHostState.showSnackbar(textoGuardado) }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.ajustes_guardar)) }
        }
    }
}
