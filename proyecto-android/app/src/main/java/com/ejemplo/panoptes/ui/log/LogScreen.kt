package com.carpe.panoptes.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carpe.panoptes.core.log.AppLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var texto by remember { mutableStateOf(AppLog.leer()) }
    var aviso by remember { mutableStateOf<String?>(null) }
    val vertical = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registro interno") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    // Se copia todo (el portapapeles aguanta cientos de KB; si no, se recorta por el final).
                    cm.setPrimaryClip(ClipData.newPlainText("Registro Panoptes", texto.takeLast(200_000)))
                    aviso = "Copiado (${texto.takeLast(200_000).length} caracteres)"
                }) { Text("Copiar") }
                OutlinedButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, texto.takeLast(200_000))
                    }
                    ctx.startActivity(Intent.createChooser(i, "Compartir registro"))
                }) { Text("Compartir") }
                OutlinedButton(onClick = { texto = AppLog.leer(); aviso = null }) { Text("Actualizar") }
                OutlinedButton(onClick = { AppLog.borrar(); texto = ""; aviso = "Registro borrado" }) { Text("Borrar") }
            }
            aviso?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxSize()) {
                SelectionContainer {
                    Text(
                        if (texto.isEmpty()) "(vacío)" else texto,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.padding(8.dp).verticalScroll(vertical).horizontalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}
