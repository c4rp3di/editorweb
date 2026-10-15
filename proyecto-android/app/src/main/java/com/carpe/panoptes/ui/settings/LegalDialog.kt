package com.carpe.panoptes.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Paragraph11 del plan (docs/LEGAL.md): dialogo de primer uso con resumen de los
 * puntos legales y un checkbox "He leido y acepto". No se puede cerrar sin
 * marcarlo, y no se vuelve a mostrar una vez aceptado (lo persiste
 * SettingsRepository via DataStore de preferencias).
 */
@Composable
fun LegalDialog(onAceptar: () -> Unit) {
    var aceptado by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { /* intencional: no se puede descartar sin aceptar */ },
        title = { Text("Antes de usar APK Panoptes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(TEXTO_LEGAL)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = aceptado, onCheckedChange = { aceptado = it })
                    Text("He leido y acepto")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAceptar, enabled = aceptado) { Text("Continuar") }
        }
    )
}

private val TEXTO_LEGAL = """
    - Uso personal exclusivamente. Panoptes no se distribuye en Google Play.
      Usalo para analizar APKs propios o de apps sobre las que tengas
      autorizacion explicita.

    - Sin ataque a servicios de terceros. El analisis es informativo
      (endpoints, permisos, ofuscacion) - esta app no fuzzea ni automatiza
      interacciones con servidores ajenos.

    - SSL unpinning y Frida (si instalas ese modulo mas adelante) pueden
      violar los terminos de servicio de apps de terceros, e incluso leyes
      de acceso no autorizado en algunas jurisdicciones, aunque el
      dispositivo sea tuyo. Solo sobre apps propias o con autorizacion
      escrita.

    - Sin telemetria. Esta app no envia datos a ningun servidor propio.
      Las descargas de modulos van exclusivamente a github.com y sus CDNs.

    - Cada analisis se guarda localmente en tu dispositivo.
""".trimIndent()
