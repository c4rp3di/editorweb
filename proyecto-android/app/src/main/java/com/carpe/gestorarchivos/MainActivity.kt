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
        setIntent(intent)
        val ruta = rutaDesdeIntent(intent) ?: return
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, crearPantalla(ruta))
            .commit()
    }

    /** PantallaGestor recibe la carpeta inicial en arguments[PantallaGestor.ARG_RUTA_INICIAL] (null = la de siempre). */
    private fun crearPantalla(ruta: String?): PantallaGestor =
        PantallaGestor().apply {
            if (ruta != null) arguments = Bundle().apply { putString(PantallaGestor.ARG_RUTA_INICIAL, ruta) }
        }

    /** Devuelve la carpeta que pide un intent VIEW (file:// o content:// del almacenamiento), o null. */
    private fun rutaDesdeIntent(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        val ruta = when (uri.scheme) {
            "file" -> uri.path
            "content" -> rutaDesdeContentUri(uri)
            else -> null
        } ?: return null
        val f = File(ruta)
        if (!f.exists()) return null
        return if (f.isDirectory) f.absolutePath else f.parentFile?.absolutePath
    }

    private fun rutaDesdeContentUri(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = try {
            if (DocumentsContract.isTreeUri(uri)) DocumentsContract.getTreeDocumentId(uri)
            else DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        val partes = id.split(":", limit = 2)
        val raiz = if (partes[0] == "primary") Environment.getExternalStorageDirectory().path
                   else "/storage/${partes[0]}"
        return if (partes.size > 1 && partes[1].isNotEmpty()) "$raiz/${partes[1]}" else raiz
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
