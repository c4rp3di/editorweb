package com.carpe.gestorarchivos

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.carpe.gestorarchivos.data.Permisos
import com.carpe.gestorarchivos.ui.PantallaGestor

class MainActivity : AppCompatActivity() {

    private val solicitarPermisosLegacy =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.contenedorPantallas, PantallaGestor())
                .commit()
            pedirPermisoArchivosSiHaceFalta()
        }
    }

    private fun pedirPermisoArchivosSiHaceFalta() {
        if (Permisos.tiene(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        } else {
            // Android 6–10: permiso de tiempo de ejecución clásico
            solicitarPermisosLegacy.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }
}
