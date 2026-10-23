package com.luzcontrol.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B57D0),
    secondary = Color(0xFF00677E),
    tertiary = Color(0xFF8C5A00)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    secondary = Color(0xFF58D5F2),
    tertiary = Color(0xFFFFB955)
)

/** Colores semánticos de precio/periodo (se acompañan siempre de texto, nunca solo color). */
object EnergyColors {
    val Cheap = Color(0xFF2E7D32)
    val Medium = Color(0xFFF9A825)
    val Expensive = Color(0xFFC62828)
    val Punta = Color(0xFFC62828)
    val Llano = Color(0xFFF9A825)
    val Valle = Color(0xFF2E7D32)
}

@Composable
fun LuzControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
