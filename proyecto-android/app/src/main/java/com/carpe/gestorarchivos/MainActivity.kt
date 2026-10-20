package com.carpe.gestorarchivos

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.carpe.gestorarchivos.data.Permisos
import com.carpe.gestorarchivos.data.ProveedorDocumentos
import com.carpe.gestorarchivos.data.ResolvedorUri
import com.carpe.gestorarchivos.ui.PermisoArchivosUi
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
            "content" -> ResolvedorUri.rutaExterna(uri)
            else -> null
        } ?: return null
        val f = File(ruta)
        if (!f.exists()) return null
        return if (f.isDirectory) f.absolutePath else f.parentFile?.absolutePath
    }

    private fun pedirPermisoArchivosSiHaceFalta() =
        PermisoArchivosUi.pedirSiHaceFalta(this, solicitarPermisosLegacy)

    override fun onResume() {
        super.onResume()
        // Si el permiso se acaba de conceder, el selector del sistema debe refrescar nuestras raíces
        if (Permisos.tiene(this)) ProveedorDocumentos.avisarCambioRaices(this)
    }
}
