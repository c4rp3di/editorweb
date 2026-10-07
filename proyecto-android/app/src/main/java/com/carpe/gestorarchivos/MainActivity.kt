package com.carpe.gestorarchivos

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.carpe.gestorarchivos.data.Permisos
import com.carpe.gestorarchivos.ui.PantallaGestor
import java.io.File

class MainActivity : AppCompatActivity() {

    private val solicitarPermisosLegacy =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.contenedorPantallas, crearPantalla(rutaDesdeIntent(intent)))
                .commit()
            pedirPermisoArchivosSiHaceFalta()
        }
    }

    // La app ya estaba abierta y otra app pide abrir una carpeta (launchMode = singleTop).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)