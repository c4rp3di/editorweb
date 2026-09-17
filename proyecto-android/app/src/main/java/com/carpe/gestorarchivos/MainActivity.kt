package com.tunombre.gestorarchivos

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tunombre.gestorarchivos.ui.PantallaGestor

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.contenedorPantallas, PantallaGestor())
                .commit()
        }

        pedirPermisoArchivosSiHaceFalta()
    }

    private fun pedirPermisoArchivosSiHaceFalta() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                AlertDialog.Builder(this)
                    .setTitle("Permiso necesario")
                    .setMessage(
                        "Para funcionar como gestor de archivos, la app necesita acceso a todos los archivos del dispositivo.\n\n" +
                        "En la siguiente pantalla, activa el interruptor «Permitir acceso a la administración de todos los archivos»."
                    )
                    .setPositiveButton("Abrir ajustes") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        try {
                            startActivity(intent)
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                    .setNegativeButton("Ahora no", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Si el usuario volvió de Ajustes sin activar el permiso, se lo recordamos.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            // Solo si la app ya está en primer plano después de un tiempo razonable
        }
    }
}