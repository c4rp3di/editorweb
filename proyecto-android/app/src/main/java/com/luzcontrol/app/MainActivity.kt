package com.luzcontrol.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.luzcontrol.app.ui.navigation.AppRoot
import com.luzcontrol.app.ui.theme.LuzControlTheme
import dagger.hilt.android.AndroidEntryPoint

/** FragmentActivity (subclase de ComponentActivity) porque BiometricPrompt lo requiere. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LuzControlTheme {
                AppRoot()
            }
        }
    }
}
