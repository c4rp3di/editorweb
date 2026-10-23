package com.luzcontrol.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Bloque con efecto "shimmer" para los estados de carga. */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, height: Dp = 80.dp) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val light = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(base, light, base), start = androidx.compose.ui.geometry.Offset(x - 400f, 0f), end = androidx.compose.ui.geometry.Offset(x, 0f)))
    )
}

@Composable
fun LoadingPlaceholder() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ShimmerBox(height = 70.dp)
        ShimmerBox(height = 120.dp)
        ShimmerBox(height = 180.dp)
        ShimmerBox(height = 90.dp)
    }
}

@Composable
fun MessagePanel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Banner amarillo para datos posiblemente desactualizados. */
@Composable
fun StaleBanner(modifier: Modifier = Modifier, text: String = "Datos posiblemente desactualizados") {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF3CD))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFF664D03))
        Text(text, color = Color(0xFF664D03), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Aviso informativo/errores no bloqueantes (por encima del contenido). */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    val bg = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(if (isError) Icons.Filled.CloudOff else Icons.Filled.Info, contentDescription = null, tint = fg)
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
        )
    }
}

/** Renderiza el estado de pantalla: shimmer, vacío, error con reintento, o contenido (con banner si está desactualizado). */
@Composable
fun <T> StateHost(
    state: UiState<T>,
    onRetry: () -> Unit,
    emptyAction: String? = null,
    onEmptyAction: (() -> Unit)? = null,
    content: @Composable (T) -> Unit
) {
    when (state) {
        UiState.Loading -> LoadingPlaceholder()
        is UiState.Empty -> MessagePanel(Icons.Filled.Info, state.message, actionLabel = emptyAction, onAction = onEmptyAction)
        is UiState.Error -> MessagePanel(Icons.Filled.CloudOff, state.message, actionLabel = "Reintentar", onAction = onRetry)
        is UiState.Success -> content(state.data)
        is UiState.Stale -> Column {
            StaleBanner()
            content(state.data)
        }
    }
}
