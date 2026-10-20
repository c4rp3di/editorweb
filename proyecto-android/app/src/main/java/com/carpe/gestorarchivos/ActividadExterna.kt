package com.carpe.gestorarchivos

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.carpe.gestorarchivos.data.Ajustes
import com.carpe.gestorarchivos.data.Almacenamientos
import com.carpe.gestorarchivos.data.ResolvedorUri
import com.carpe.gestorarchivos.data.SolicitudExterna
import com.carpe.gestorarchivos.data.SolicitudExterna.Tipo
import com.carpe.gestorarchivos.ui.AnfitrionExterno
import com.carpe.gestorarchivos.ui.PantallaGestor
import com.carpe.gestorarchivos.ui.PermisoArchivosUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Entrada desde otras apps: «Guardar como…», «Elegir archivo/carpeta», «Compartir con…» y «Abrir con…».
 * Aloja a [PantallaGestor] en el modo que corresponda. Se ejecuta en su propia tarea, sin tocar
 * la sesión que el usuario tenga abierta en [MainActivity].
 */
class ActividadExterna : AppCompatActivity(), AnfitrionExterno {

    private var solicitudActual: SolicitudExterna = SolicitudExterna(Tipo.ABRIR)
    override val solicitud: SolicitudExterna get() = solicitudActual

    private val permisosLegacy =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Si el sistema recreó la actividad (proceso eliminado) se perdió la petición: se cancela.
        if (savedInstanceState != null) {
            cancelar()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) { ResolvedorUri.purgarCache(applicationContext) }
        PermisoArchivosUi.pedirSiHaceFalta(this, permisosLegacy)
        interpretar(intent)
    }

    // ------------------------------------------------------------------ AnfitrionExterno

    override fun terminarConResultado(resultado: Intent) {
        setResult(RESULT_OK, resultado)
        finish()
    }

    override fun cancelar() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun terminar() {
        setResult(RESULT_OK)
        finish()
    }

    // ------------------------------------------------------------------ interpretar la petición

    private fun interpretar(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT -> {
                val mimes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                    ?.filter { it.isNotBlank() }
                    ?: listOfNotNull(intent.type)
                val pideEscritura = intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
                mostrar(
                    SolicitudExterna(
                        Tipo.ELEGIR_ARCHIVO,
                        multiple = intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
                        mimes = mimes,
                        pideEscritura = pideEscritura
                    ),
                    Almacenamientos.descargas(), null
                )
            }
            Intent.ACTION_OPEN_DOCUMENT_TREE ->
                mostrar(SolicitudExterna(Tipo.ELEGIR_CARPETA), Almacenamientos.descargas(), null)
            Intent.ACTION_CREATE_DOCUMENT -> {
                val nombre = intent.getStringExtra(Intent.EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "documento"
                mostrar(
                    SolicitudExterna(Tipo.CREAR_DOCUMENTO, nombreSugerido = nombre),
                    carpetaDeGuardado(), null
                )
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> recibir(intent)
            Intent.ACTION_VIEW -> abrirVista(intent)
            else -> cancelar()
        }
    }

    private fun carpetaDeGuardado(): File {
        val ultima = Ajustes(this).ultimaCarpetaGuardado?.let { File(it) }
        return if (ultima != null && ultima.isDirectory && ultima.canWrite()) ultima else Almacenamientos.descargas()
    }

    private fun mostrar(s: SolicitudExterna, carpetaInicial: File, abrir: File?) {
        solicitudActual = s
        val pantalla = PantallaGestor().apply {
            arguments = Bundle().apply {
                putString(PantallaGestor.ARG_RUTA_INICIAL, carpetaInicial.absolutePath)
                if (abrir != null) putString(PantallaGestor.ARG_ABRIR, abrir.absolutePath)
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, pantalla)
            .commit()
    }

    // ------------------------------------------------------------------ compartir hacia la app

    private fun recibir(intent: Intent) {
        val uris = urisCompartidos(intent)
        val texto = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && texto.isNullOrEmpty()) {
            Toast.makeText(this, "No hay nada que guardar", Toast.LENGTH_SHORT).show()
            cancelar()
            return
        }
        val fuentes = uris.map {
            SolicitudExterna.Fuente(it, ResolvedorUri.nombreDe(this, it, if (uris.size == 1) intent.type else null))
        }
        val sugerido = if (fuentes.isEmpty()) nombreParaTexto(texto ?: "") else fuentes.singleOrNull()?.nombre
        mostrar(
            SolicitudExterna(
                Tipo.RECIBIR,
                fuentes = fuentes,
                textoRecibido = if (fuentes.isEmpty()) texto else null,
                nombreSugerido = sugerido
            ),
            carpetaDeGuardado(), null
        )
    }

    @Suppress("DEPRECATION")
    private fun urisCompartidos(intent: Intent): List<Uri> {
        val lista = ArrayList<Uri>()
        val unico = intent.action == Intent.ACTION_SEND
        if (Build.VERSION.SDK_INT >= 33) {
            if (unico) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { lista.add(it) }
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { lista.addAll(it) }
        } else {
            if (unico) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { lista.add(it) }
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { lista.addAll(it) }
        }
        if (lista.isEmpty()) {
            val clip = intent.clipData
            if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { lista.add(it) }
        }
        return lista
    }

    private fun nombreParaTexto(texto: String): String {
        val primera = texto.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return "texto.txt"
        val limpio = primera.replace(Regex("[\\\\/:*?\"<>|\\u0000]"), "_").take(40).trim()
        return if (limpio.isEmpty()) "texto.txt" else "$limpio.txt"
    }

    // ------------------------------------------------------------------ abrir con la app

    private fun abrirVista(intent: Intent) {
        val uri = intent.data
        if (uri == null) {
            cancelar()
            return
        }
        val real = ResolvedorUri.aArchivo(this, uri)
        if (real != null) {
            mostrar(
                SolicitudExterna(Tipo.ABRIR, archivoAAbrir = real),
                real.parentFile ?: Almacenamientos.descargas(), real
            )
            return
        }
        // Sin ruta real (descargas del navegador, WhatsApp, correo…): se copia a la caché
        val nombre = ResolvedorUri.nombreDe(this, uri, intent.type)
        val vista = layoutInflater.inflate(R.layout.dialog_progreso, null)
        vista.findViewById<TextView>(R.id.textoProgreso).text = "Preparando «$nombre»…"
        val dialogo = AlertDialog.Builder(this).setView(vista).setCancelable(false).create()
        dialogo.show()
        lifecycleScope.launch {
            val copia = withContext(Dispatchers.IO) { ResolvedorUri.copiarACache(applicationContext, uri, nombre) }
            if (dialogo.isShowing) dialogo.dismiss()
            if (isFinishing) return@launch
            if (copia == null) {
                Toast.makeText(this@ActividadExterna, "No se pudo leer el archivo", Toast.LENGTH_LONG).show()
                cancelar()
            } else {
                preguntarQueHacer(copia)
            }
        }
    }

    private fun preguntarQueHacer(copia: File) {
        AlertDialog.Builder(this)
            .setTitle(copia.name)
            .setItems(arrayOf("Abrir", "Guardar en el dispositivo…")) { _, i ->
                if (i == 0) {
                    mostrar(
                        SolicitudExterna(Tipo.ABRIR, archivoAAbrir = copia),
                        Almacenamientos.descargas(), copia
                    )
                } else {
                    mostrar(
                        SolicitudExterna(
                            Tipo.RECIBIR,
                            fuentes = listOf(SolicitudExterna.Fuente(Uri.fromFile(copia), copia.name)),
                            nombreSugerido = copia.name
                        ),
                        carpetaDeGuardado(), null
                    )
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> cancelar() }
            .setOnCancelListener { cancelar() }
            .show()
    }
}
