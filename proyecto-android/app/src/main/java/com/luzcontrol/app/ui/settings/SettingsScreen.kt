package com.luzcontrol.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luzcontrol.app.domain.model.BillingParams
import com.luzcontrol.app.domain.model.Supply
import com.luzcontrol.app.ui.components.Fmt
import com.luzcontrol.app.ui.components.MessagePanel
import com.luzcontrol.app.ui.components.NoticeBanner
import com.luzcontrol.app.ui.components.SectionCard
import com.luzcontrol.app.ui.navigation.BiometricGate
import com.luzcontrol.app.ui.navigation.MainViewModel

private fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var unlocked by remember { mutableStateOf(mainViewModel.settingsUnlocked) }
    var authError by remember { mutableStateOf<String?>(null) }

    // Se vuelve a bloquear al salir de la pestaña
    DisposableEffect(Unit) {
        onDispose { mainViewModel.settingsUnlocked = false }
    }

    val activity = context.findFragmentActivity()
    val needsGate = settings.requireBiometric && activity != null && BiometricGate.isAvailable(activity)

    if (needsGate && !unlocked) {
        fun ask() {
            BiometricGate.prompt(
                activity!!, "Ajustes protegidos", "Confirma tu identidad para ver tus datos",
                onSuccess = { mainViewModel.settingsUnlocked = true; unlocked = true; authError = null },
                onFailure = { authError = it }
            )
        }
        LaunchedEffect(Unit) { ask() }
        MessagePanel(
            icon = Icons.Filled.Lock,
            message = authError ?: "Autentícate para acceder a los ajustes.",
            actionLabel = "Desbloquear",
            onAction = { ask() }
        )
        return
    }

    SettingsContent(viewModel, settings)
}

@Composable
private fun SettingsContent(viewModel: SettingsViewModel, settings: com.luzcontrol.app.domain.model.UserSettings) {
    val context = LocalContext.current
    val hasCreds by viewModel.hasCredentials.collectAsStateWithLifecycle()
    val validation by viewModel.validation.collectAsStateWithLifecycle()
    val supplies by viewModel.supplies.collectAsStateWithLifecycle()
    val activeCups by viewModel.activeCups.collectAsStateWithLifecycle()
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is SettingsEvent.Message -> Toast.makeText(context, e.text, Toast.LENGTH_LONG).show()
                is SettingsEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(e.file.mimeType)
                        .putExtra(Intent.EXTRA_STREAM, Uri.parse(e.file.uri))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Compartir ${e.file.fileName}"))
                }
            }
        }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setPriceNotifications(granted)
        if (!granted) Toast.makeText(context, "Sin permiso de notificaciones no se pueden enviar avisos.", Toast.LENGTH_LONG).show()
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { CredentialsCard(viewModel, hasCreds, validation) }
        item { SuppliesCard(viewModel, supplies, activeCups) }
        item {
            SectionCard("Sincronización y avisos") {
                SwitchRow("Sincronización diaria automática", settings.dailySync) { viewModel.setDailySync(it) }
                SwitchRow("Avisos de precio (muy barato / muy caro)", settings.priceNotifications) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else viewModel.setPriceNotifications(on)
                }
                SwitchRow("Pedir huella / PIN para abrir Ajustes", settings.requireBiometric) { viewModel.setRequireBiometric(it) }
                SwitchRow("API de Datadis v2 (si falla, prueba v1)", settings.datadisApiV2) { viewModel.setApiV2(it) }
                SwitchRow("Fuente UFD directa (no disponible aún)", false, enabled = false) { }
            }
        }
        item { EsiosCard(viewModel) }
        item { BillingCard(viewModel, settings.billing) }
        item {
            SectionCard("Exportación de datos", subtitle = "Del suministro activo") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.export(false) }, enabled = !exporting) { Text("Exportar CSV") }
                    OutlinedButton(onClick = { viewModel.export(true) }, enabled = !exporting) { Text("Informe PDF") }
                }
                if (exporting) Text("Generando archivo…")
            }
        }
        item { LegalCard(viewModel) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun CredentialsCard(viewModel: SettingsViewModel, hasCreds: Boolean, validation: ValidationState) {
    var user by remember { mutableStateOf(viewModel.savedUsername) }
    var pass by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    SectionCard(
        "Credenciales de Datadis",
        subtitle = if (hasCreds) "Guardadas cifradas en este dispositivo" else "Aún no configuradas"
    ) {
        OutlinedTextField(
            value = user, onValueChange = { user = it }, label = { Text("DNI / NIE") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text(if (hasCreds) "Contraseña (déjala vacía para conservar la guardada)" else "Contraseña") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Ocultar contraseña" else "Mostrar contraseña"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.validateAndSave(user, pass) },
                enabled = validation !is ValidationState.Validating && pass.isNotBlank()
            ) { Text("Validar y guardar") }
            if (hasCreds) TextButton(onClick = { viewModel.clearCredentials(); pass = "" }) { Text("Borrar") }
        }
        when (validation) {
            ValidationState.Idle -> {}
            ValidationState.Validating -> Text("Validando con Datadis…")
            is ValidationState.Ok -> NoticeBanner("Credenciales correctas. Suministros encontrados: ${validation.supplies}. Sincronizando…")
            is ValidationState.Failed -> NoticeBanner(validation.message, isError = true)
        }
    }
}

@Composable
private fun SuppliesCard(viewModel: SettingsViewModel, supplies: List<Supply>, active: String?) {
    SectionCard("Suministro activo (CUPS)", subtitle = "Es el que se muestra en el resto de pestañas") {
        if (supplies.isEmpty()) {
            Text("Aún no hay suministros. Valida tus credenciales para descargarlos.")
        } else {
            supplies.forEach { s ->
                var editing by remember(s.cups) { mutableStateOf(false) }
                var alias by remember(s.cups, s.alias) { mutableStateOf(s.alias.orEmpty()) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.cups == active, onClick = { viewModel.setActiveCups(s.cups) })
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(s.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${s.cups} · ${s.distributor}".trim(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { editing = !editing }) { Text("Alias") }
                }
                if (editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(alias, { alias = it }, singleLine = true, label = { Text("Alias") }, modifier = Modifier.weight(1f))
                        Button(onClick = { viewModel.setAlias(s.cups, alias); editing = false }) { Text("OK") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EsiosCard(viewModel: SettingsViewModel) {
    var token by remember { mutableStateOf("") }
    SectionCard(
        "Token de ESIOS (opcional)",
        subtitle = if (viewModel.hasEsiosToken) "Token guardado cifrado" else "Sin token: se usa el endpoint público de REE"
    ) {
        OutlinedTextField(
            token, { token = it }, singleLine = true, label = { Text("Token personal de ESIOS") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.saveEsiosToken(token); token = "" }, enabled = token.isNotBlank()) { Text("Guardar") }
            if (viewModel.hasEsiosToken) TextButton(onClick = { viewModel.saveEsiosToken("") }) { Text("Quitar") }
        }
    }
}

@Composable
private fun BillingCard(viewModel: SettingsViewModel, b: BillingParams) {
    var p1 by remember(b) { mutableStateOf(Fmt.num(b.powerP1EurKwDay, 6)) }
    var p2 by remember(b) { mutableStateOf(Fmt.num(b.powerP2EurKwDay, 6)) }
    var meter by remember(b) { mutableStateOf(Fmt.num(b.meterRentEurDay, 5)) }
    var bono by remember(b) { mutableStateOf(Fmt.num(b.socialBonusEurDay, 6)) }
    var tax by remember(b) { mutableStateOf(Fmt.num(b.electricityTaxPct, 5)) }
    var iva by remember(b) { mutableStateOf(Fmt.num(b.ivaPct, 1)) }
    SectionCard(
        "Parámetros del simulador de factura",
        subtitle = "Valores orientativos de la tarifa 2.0TD: revísalos con tu factura"
    ) {
        BillingField("Potencia P1 (€/kW·día)", p1) { p1 = it }
        BillingField("Potencia P2 (€/kW·día)", p2) { p2 = it }
        BillingField("Alquiler contador (€/día)", meter) { meter = it }
        BillingField("Bono social (€/día)", bono) { bono = it }
        BillingField("Impuesto eléctrico (%)", tax) { tax = it }
        BillingField("IVA (%)", iva) { iva = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val def = BillingParams()
                viewModel.setBilling(
                    BillingParams(
                        powerP1EurKwDay = Fmt.parseDouble(p1) ?: def.powerP1EurKwDay,
                        powerP2EurKwDay = Fmt.parseDouble(p2) ?: def.powerP2EurKwDay,
                        meterRentEurDay = Fmt.parseDouble(meter) ?: def.meterRentEurDay,
                        socialBonusEurDay = Fmt.parseDouble(bono) ?: def.socialBonusEurDay,
                        electricityTaxPct = Fmt.parseDouble(tax) ?: def.electricityTaxPct,
                        ivaPct = Fmt.parseDouble(iva) ?: def.ivaPct
                    )
                )
            }) { Text("Guardar") }
            OutlinedButton(onClick = { viewModel.resetBilling() }) { Text("Restaurar") }
        }
    }
}

@Composable
private fun BillingField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LegalCard(viewModel: SettingsViewModel) {
    var confirm by remember { mutableStateOf(false) }
    SectionCard("Privacidad e información legal") {
        Text(
            "Tu DNI y tu contraseña de Datadis se guardan cifrados (Android Keystore) solo en este dispositivo y " +
                "únicamente se envían a datadis.es para descargar tus datos. LuzControl no tiene servidores propios ni " +
                "recoge información personal. Los datos proceden de Datadis (distribuidora UFD) y de Red Eléctrica " +
                "(ESIOS/REE). Los importes simulados son estimaciones y no sustituyen a la factura oficial.",
            style = MaterialTheme.typography.bodySmall
        )
        if (!confirm) {
            OutlinedButton(onClick = { confirm = true }) { Text("Borrar datos locales") }
        } else {
            Text("Se borrarán consumos, precios y contratos guardados (podrás volver a descargarlos).")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.clearLocalData(); confirm = false }) { Text("Confirmar borrado") }
                TextButton(onClick = { confirm = false }) { Text("Cancelar") }
            }
        }
    }
}
