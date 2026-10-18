package com.carpe.panoptes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.carpe.panoptes.ui.PanoptesAppRoot

/**
 * F3: single-activity. Toda la navegacion (lista de apps, analisis,
 * modulos) vive dentro de PanoptesAppRoot via Navigation Compose
 * (ui/nav/PanoptesNavHost.kt) en vez de una Activity por pantalla.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PanoptesAppRoot()
        }
    }
}
.