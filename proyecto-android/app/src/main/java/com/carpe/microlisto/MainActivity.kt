package com.carpe.microlisto

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.carpe.microlisto.AppLogic
import androidx.fragment.app.Fragment
import com.carpe.microlisto.ui.PantallaEjemplo
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import android.content.SharedPreferences
import com.carpe.microlisto.data.BaseDatos
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.core.content.FileProvider
import android.app.PendingIntent
import com.carpe.microlisto.predictor.OnnxPredictor
import android.os.Environment
import android.provider.Settings
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
    // Funcionalidad: base-datos
    private lateinit var baseDatos: BaseDatos
    // Funcionalidad: microfono
    private var grabadorAudio: MediaRecorder? = null
    private val lanzadorPermisoMicrofono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) iniciarGrabacionAudio() else Toast.makeText(this, "Permiso de micrófono denegado", Toast.LENGTH_SHORT).show()
    }
    // Funcionalidad: notificaciones
    private val lanzadorPermisoNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // [PROPIEDADES:FUNCIONALIDADES]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("datos_app", MODE_PRIVATE)
        baseDatos = BaseDatos(this)
        crearCanalNotificaciones()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            lanzadorPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Funcionalidad: pantalla-encendida
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canalAcciones = NotificationChannel("canal_acciones", "Notificaciones con acciones", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canalAcciones)
        }
        // [ONCREATE:FUNCIONALIDADES]
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) mostrarPantalla(PantallaEjemplo())
        configurarBarraInferior()
        // [ONCREATE_FIN:FUNCIONALIDADES]
        AppLogic.onIniciar(this)
    }

    // Funcionalidad: varias-pantallas
    private fun mostrarPantalla(pantalla: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, pantalla)
            .commit()
    }

    // Funcionalidad: barra-inferior
    private fun configurarBarraInferior() {
        findViewById<BottomNavigationView>(R.id.barraInferior).setOnItemSelectedListener { item ->
            // if (item.itemId == R.id.navInicio) mostrarPantalla(PantallaEjemplo())
            true
        }
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

    private fun grabarAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            iniciarGrabacionAudio()
        } else {
            lanzadorPermisoMicrofono.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun iniciarGrabacionAudio() {
        val archivoSalida = File(cacheDir, "grabacion_${System.currentTimeMillis()}.m4a").absolutePath
        grabadorAudio = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(archivoSalida)
            prepare()
            start()
        }
    }

    private fun detenerGrabacionAudio() {
        grabadorAudio?.apply { stop(); release() }
        grabadorAudio = null
    }

    private fun crearCanalNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel("canal_app", "Notificaciones", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun mostrarNotificacion(titulo: String, mensaje: String) {
        val notificacion = NotificationCompat.Builder(this, "canal_app")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titulo)
            .setContentText(mensaje)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify(1, notificacion)
    }

    // Funcionalidad: vibracion
    private fun vibrar(duracionMs: Long = 200) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duracionMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duracionMs)
        }
    }

    // Funcionalidad: compartir-archivos
    private fun compartirArchivo(archivo: File, tipoMime: String = "*/*") {
        val uri = FileProvider.getUriForFile(this, "com.carpe.microlisto.fileprovider", archivo)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = tipoMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, null))
    }

    // Funcionalidad: notificaciones-acciones
    private fun mostrarNotificacionConAcciones(titulo: String, mensaje: String) {
        val intentAccion = Intent(this, MainActivity::class.java).apply {
            action = "ACCION_NOTIFICACION"
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intentAccion,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificacion = NotificationCompat.Builder(this, "canal_acciones")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titulo)
            .setContentText(mensaje)
            .addAction(0, "Abrir", pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify(2, notificacion)
    }

    // Funcionalidad: servicio-foreground
    private fun iniciarServicioPrimerPlano() {
        val intent = Intent(this, ServicioPrimerPlano::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun detenerServicioPrimerPlano() {
        stopService(Intent(this, ServicioPrimerPlano::class.java))
    }

    // Funcionalidad: onnxruntime
    // Copia tu modelo en app/src/main/assets/modelo.onnx y luego:
    //   val predictor = OnnxPredictor(this)
    //   val salida = predictor.predecir(datosDeEntrada, formaDeEntrada)

    // Funcionalidad: acceso-total-archivos
    // ⚠️ MANAGE_EXTERNAL_STORAGE es un permiso especial: Google Play lo mira
    // con lupa y puede pedir justificación para publicarlo en la tienda. La
    // mayoría de apps generadas aquí son para uso propio (instalación directa
    // por APK), así que el permiso suele ser útil aun así — revisa las
    // políticas de Play antes de publicar con esta funcionalidad activa.
    private fun pedirAccesoTotalArchivos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun tieneAccesoTotalArchivos(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    // [METODOS:FUNCIONALIDADES]
}
