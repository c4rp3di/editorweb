package com.ejemplo.proxy

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ejemplo.proxy.AppLogic
import androidx.fragment.app.Fragment
import com.ejemplo.proxy.ui.PantallaWeb
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
// [IMPORTS:FUNCIONALIDADES]

class MainActivity : AppCompatActivity() {

    // Funcionalidad: saf
    private var carpetaSaf: Uri? = null
    private val lanzadorSaf = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            carpetaSaf = uri
        }
    }
    // Funcionalidad: preferencias
    private lateinit var prefs: SharedPreferences
    // [PROPIEDADES:FUNCIONALIDADES]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("datos_app", MODE_PRIVATE)
        // [ONCREATE:FUNCIONALIDADES]
        setContentView(R.layout.activity_main)
        // [ONCREATE_FIN:FUNCIONALIDADES]
        AppLogic.onIniciar(this)
    }

    // Funcionalidad: varias-pantallas
    private fun mostrarPantalla(pantalla: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, pantalla)
            .commit()
    }

    // Funcionalidad: pantalla-web
    private fun mostrarPantallaWebFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, PantallaWeb())
            .commit()
    }

    private fun elegirCarpeta() {
        lanzadorSaf.launch(null)
    }

    private fun listarArchivosDeCarpeta(): List<DocumentFile> {
        val carpeta = carpetaSaf ?: return emptyList()
        val documento = DocumentFile.fromTreeUri(this, carpeta) ?: return emptyList()
        return documento.listFiles().toList()
    }

    private fun escribirArchivoEnCarpeta(nombre: String, contenido: String) {
        val carpeta = carpetaSaf ?: return
        val documento = DocumentFile.fromTreeUri(this, carpeta) ?: return
        val archivo = documento.findFile(nombre) ?: documento.createFile("text/plain", nombre) ?: return
        contentResolver.openOutputStream(archivo.uri)?.use { it.write(contenido.toByteArray()) }
    }

    private fun leerArchivoDeCarpeta(nombre: String): String? {
        val carpeta = carpetaSaf ?: return null
        val documento = DocumentFile.fromTreeUri(this, carpeta) ?: return null
        val archivo = documento.findFile(nombre) ?: return null
        return contentResolver.openInputStream(archivo.uri)?.bufferedReader()?.use { it.readText() }
    }

    private fun guardarPreferencia(clave: String, valor: String) {
        prefs.edit().putString(clave, valor).apply()
    }

    private fun leerPreferencia(clave: String, porDefecto: String = ""): String {
        return prefs.getString(clave, porDefecto) ?: porDefecto
    }

    // Funcionalidad: descargar
    private fun descargarTexto(url: String, alTerminar: (String?) -> Unit) {
        Thread {
            try {
                val conexion = URL(url).openConnection() as HttpURLConnection
                conexion.requestMethod = "GET"
                val resultado = conexion.inputStream.bufferedReader().use { it.readText() }
                runOnUiThread { alTerminar(resultado) }
            } catch (e: Exception) {
                runOnUiThread { alTerminar(null) }
            }
        }.start()
    }

    // Funcionalidad: copiar-portapapeles
    private fun copiarAlPortapapeles(texto: String, etiqueta: String = "texto") {
        val gestor = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        gestor.setPrimaryClip(ClipData.newPlainText(etiqueta, texto))
    }

    // [METODOS:FUNCIONALIDADES]
}
