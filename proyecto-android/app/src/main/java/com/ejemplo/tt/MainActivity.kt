package com.ejemplo.tt

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ejemplo.tt.AppLogic
import androidx.fragment.app.Fragment
import com.ejemplo.tt.ui.PantallaEjemplo
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatDelegate
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ejemplo.tt.data.BaseDatos
import com.ejemplo.tt.data.AppRoomDatabase
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.io.File
import android.media.MediaRecorder
import android.speech.RecognizerIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.net.HttpURLConnection
import java.net.URL
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.WindowManager
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.core.content.FileProvider
import android.telephony.SmsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.provider.ContactsContract
import android.provider.CalendarContract
import android.app.PendingIntent
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import androidx.core.os.LocaleListCompat
import com.ejemplo.tt.predictor.OnnxPredictor
import org.opencv.android.OpenCVLoader
import android.os.Environment
import android.provider.Settings
// [IMPORTS:FUNCIONALIDADES]

class MainActivity : AppCompatActivity() {

    // Funcionalidad: menu-lateral
    private lateinit var drawerLayout: DrawerLayout
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
    // Funcionalidad: preferencias-cifradas
    private lateinit var almacenCifrado: SharedPreferences
    // Funcionalidad: base-datos
    private lateinit var baseDatos: BaseDatos
    // Funcionalidad: room
    private lateinit var bdRoom: AppRoomDatabase
    // Funcionalidad: camara
    private val lanzadorPermisoCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) tomarFoto() else Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
    }
    private val lanzadorFotoCamara = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        // bitmap contiene la foto en miniatura; guárdala o muéstrala aquí
    }
    // Funcionalidad: camara-tiempo-real
    private var imageCapture: ImageCapture? = null
    private val lanzadorPermisoCameraX = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) iniciarCameraX() else Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
    }
    // Funcionalidad: galeria
    private val lanzadorGaleria = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // uri contiene la imagen elegida (o null si el usuario canceló)
    }
    // Funcionalidad: microfono
    private var grabadorAudio: MediaRecorder? = null
    private val lanzadorPermisoMicrofono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) iniciarGrabacionAudio() else Toast.makeText(this, "Permiso de micrófono denegado", Toast.LENGTH_SHORT).show()
    }
    // Funcionalidad: voz
    private val lanzadorPermisoVoz = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) iniciarReconocimientoVoz() else Toast.makeText(this, "Permiso de micrófono denegado", Toast.LENGTH_SHORT).show()
    }
    private val lanzadorReconocimientoVoz = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        val texto = resultado.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        // texto contiene lo que el usuario dijo (o null si canceló o falló)
    }
    // Funcionalidad: notificaciones
    private val lanzadorPermisoNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Funcionalidad: descargar-progreso
    private val receptorDescarga = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // la descarga con este id ha terminado (con éxito o con error)
            val idDescarga = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        }
    }
    // Funcionalidad: linterna
    private var linternaEncendida = false
    // Funcionalidad: acelerometro / brujula
    private lateinit var gestorSensores: SensorManager
    // Funcionalidad: podometro
    private val lanzadorPermisoPodometro = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) activarPodometro() else Toast.makeText(this, "Permiso de actividad física denegado", Toast.LENGTH_SHORT).show()
    }
    // Funcionalidad: ubicacion
    private val lanzadorPermisoUbicacion = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) obtenerUbicacionActual() else Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_SHORT).show()
    }
    // Funcionalidad: huella
    private lateinit var promptHuella: BiometricPrompt
    // Funcionalidad: reproducir-audio
    private var reproductorAudio: MediaPlayer? = null
    // Funcionalidad: bluetooth
    private val receptorBluetooth = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                @Suppress("DEPRECATION")
                val dispositivo = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                // dispositivo encontrado; usa dispositivo?.name / dispositivo?.address aquí
            }
        }
    }
    private val lanzadorPermisosBluetooth = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultados ->
        if (resultados.values.all { it }) {
            iniciarBusquedaBluetooth()
        } else {
            Toast.makeText(this, "Permisos de Bluetooth denegados", Toast.LENGTH_SHORT).show()
        }
    }
    // Funcionalidad: nfc
    private var adaptadorNfc: NfcAdapter? = null
    // Funcionalidad: enviar-sms
    private var smsPendiente: Pair<String, String>? = null
    private val lanzadorPermisoSms = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) {
            smsPendiente?.let { (numero, mensaje) -> enviarSmsDirecto(numero, mensaje) }
        } else {
            Toast.makeText(this, "Permiso de SMS denegado", Toast.LENGTH_SHORT).show()
        }
        smsPendiente = null
    }
    // Funcionalidad: guardar-galeria
    private var imagenPendienteGaleria: Pair<Bitmap, String>? = null
    private val lanzadorPermisoGaleria = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) {
            imagenPendienteGaleria?.let { (bitmap, nombre) -> guardarImagenEnGaleria(bitmap, nombre) }
        } else {
            Toast.makeText(this, "Permiso de almacenamiento denegado", Toast.LENGTH_SHORT).show()
        }
        imagenPendienteGaleria = null
    }
    // Funcionalidad: leer-contacto
    private val lanzadorPermisoContactos = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) leerContactos() else Toast.makeText(this, "Permiso de contactos denegado", Toast.LENGTH_SHORT).show()
    }
    // Funcionalidad: leer-calendario
    private val lanzadorPermisoCalendario = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) leerEventosCalendario() else Toast.makeText(this, "Permiso de calendario denegado", Toast.LENGTH_SHORT).show()
    }
    // [PROPIEDADES:FUNCIONALIDADES]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Funcionalidad: modo-oscuro
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        prefs = getSharedPreferences("datos_app", MODE_PRIVATE)
        val claveMaestra = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        almacenCifrado = EncryptedSharedPreferences.create(
            this,
            "datos_app_cifrados",
            claveMaestra,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        baseDatos = BaseDatos(this)
        bdRoom = AppRoomDatabase.obtener(this)
        crearCanalNotificaciones()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            lanzadorPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val filtroDescarga = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receptorDescarga, filtroDescarga, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receptorDescarga, filtroDescarga)
        }
        // Funcionalidad: pantalla-encendida
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gestorSensores = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        promptHuella = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(resultado: BiometricPrompt.AuthenticationResult) {
                // autenticación correcta
            }
            override fun onAuthenticationError(codigoError: Int, mensaje: CharSequence) {
                Toast.makeText(this@MainActivity, "Error: $mensaje", Toast.LENGTH_SHORT).show()
            }
        })
        val filtroBluetooth = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receptorBluetooth, filtroBluetooth, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receptorBluetooth, filtroBluetooth)
        }
        adaptadorNfc = NfcAdapter.getDefaultAdapter(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canalAcciones = NotificationChannel("canal_acciones", "Notificaciones con acciones", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canalAcciones)
        }
        // Funcionalidad: opencv
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "No se pudo inicializar OpenCV", Toast.LENGTH_SHORT).show()
        }
        // [ONCREATE:FUNCIONALIDADES]
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) mostrarPantalla(PantallaEjemplo())
        drawerLayout = findViewById(R.id.drawerLayout)
        findViewById<NavigationView>(R.id.navigationView).setNavigationItemSelectedListener {
            drawerLayout.closeDrawers()
            true
        }
        configurarBarraInferior()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            iniciarCameraX()
        } else {
            lanzadorPermisoCameraX.launch(Manifest.permission.CAMERA)
        }
        manejarEnlaceEntrante()
        // [ONCREATE_FIN:FUNCIONALIDADES]
        AppLogic.onIniciar(this)
    }

    // Funcionalidad: varias-pantallas
    private fun mostrarPantalla(pantalla: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, pantalla)
            .commit()
    }

    private fun abrirMenuLateral() {
        drawerLayout.openDrawer(GravityCompat.START)
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

    private fun guardarEnAlmacenCifrado(clave: String, valor: String) {
        almacenCifrado.edit().putString(clave, valor).apply()
    }

    private fun leerDeAlmacenCifrado(clave: String, porDefecto: String = ""): String {
        return almacenCifrado.getString(clave, porDefecto) ?: porDefecto
    }

    private fun pedirFotoConCamara() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            tomarFoto()
        } else {
            lanzadorPermisoCamara.launch(Manifest.permission.CAMERA)
        }
    }

    private fun tomarFoto() {
        lanzadorFotoCamara.launch(null)
    }

    private fun iniciarCameraX() {
        val proveedorFuturo = ProcessCameraProvider.getInstance(this)
        proveedorFuturo.addListener({
            val proveedorCamara = proveedorFuturo.get()
            val vistaPrevia = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.vistaPreviaCamara).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val analisisImagen = ImageAnalysis.Builder().build()
            try {
                proveedorCamara.unbindAll()
                proveedorCamara.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, vistaPrevia, imageCapture, analisisImagen
                )
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo iniciar la cámara: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tomarFotoCameraX(archivo: File, alTerminar: (Boolean) -> Unit) {
        val captura = imageCapture ?: return
        val opciones = ImageCapture.OutputFileOptions.Builder(archivo).build()
        captura.takePicture(opciones, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(salida: ImageCapture.OutputFileResults) = alTerminar(true)
            override fun onError(error: ImageCaptureException) = alTerminar(false)
        })
    }

    private fun elegirImagenDeGaleria() {
        lanzadorGaleria.launch("image/*")
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

    private fun pedirReconocimientoVoz() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            iniciarReconocimientoVoz()
        } else {
            lanzadorPermisoVoz.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun iniciarReconocimientoVoz() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        lanzadorReconocimientoVoz.launch(intent)
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

    // Funcionalidad: vibracion-patron
    private fun vibrarPatron(patronMs: LongArray, repetirDesde: Int = -1) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(patronMs, repetirDesde))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(patronMs, repetirDesde)
        }
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

    private fun descargarConProgreso(url: String, nombreArchivo: String): Long {
        val gestor = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val peticion = DownloadManager.Request(Uri.parse(url))
            .setTitle(nombreArchivo)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, null, nombreArchivo)
        return gestor.enqueue(peticion)
    }

    // Funcionalidad: enviar
    private fun enviarDatos(url: String, cuerpoJson: String, alTerminar: (Int) -> Unit) {
        Thread {
            try {
                val conexion = URL(url).openConnection() as HttpURLConnection
                conexion.requestMethod = "POST"
                conexion.setRequestProperty("Content-Type", "application/json")
                conexion.doOutput = true
                conexion.outputStream.use { it.write(cuerpoJson.toByteArray()) }
                val codigo = conexion.responseCode
                runOnUiThread { alTerminar(codigo) }
            } catch (e: Exception) {
                runOnUiThread { alTerminar(-1) }
            }
        }.start()
    }

    // Funcionalidad: conexion
    private fun hayConexionInternet(): Boolean {
        val gestor = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val red = gestor.activeNetwork ?: return false
        val capacidades = gestor.getNetworkCapabilities(red) ?: return false
        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Funcionalidad: abrir-enlaces
    private fun abrirEnlace(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    // Funcionalidad: modo-inmersivo
    private fun activarModoInmersivo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun desactivarModoInmersivo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun alternarLinterna() {
        val gestor = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val idCamara = gestor.cameraIdList.firstOrNull { id ->
            gestor.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        linternaEncendida = !linternaEncendida
        gestor.setTorchMode(idCamara, linternaEncendida)
    }

    // Funcionalidad: brujula
    private val listenerBrujula = object : SensorEventListener {
        override fun onSensorChanged(evento: SensorEvent) {
            val matrizRotacion = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(matrizRotacion, evento.values)
            val orientacion = FloatArray(3)
            SensorManager.getOrientation(matrizRotacion, orientacion)
            val gradosAzimut = Math.toDegrees(orientacion[0].toDouble()).toFloat()
            // gradosAzimut: 0=Norte, 90=Este, 180=Sur, 270=Oeste
        }
        override fun onAccuracyChanged(sensor: Sensor, precision: Int) {}
    }

    private fun activarBrujula() {
        val sensor = gestorSensores.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gestorSensores.registerListener(listenerBrujula, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun desactivarBrujula() {
        gestorSensores.unregisterListener(listenerBrujula)
    }

    // Funcionalidad: acelerometro
    private val listenerAcelerometro = object : SensorEventListener {
        override fun onSensorChanged(evento: SensorEvent) {
            val x = evento.values[0]
            val y = evento.values[1]
            val z = evento.values[2]
            // usa x, y, z aquí
        }
        override fun onAccuracyChanged(sensor: Sensor, precision: Int) {}
    }

    private fun activarAcelerometro() {
        val sensor = gestorSensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gestorSensores.registerListener(listenerAcelerometro, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun desactivarAcelerometro() {
        gestorSensores.unregisterListener(listenerAcelerometro)
    }

    // Funcionalidad: giroscopio
    private val listenerGiroscopio = object : SensorEventListener {
        override fun onSensorChanged(evento: SensorEvent) {
            val velX = evento.values[0]
            val velY = evento.values[1]
            val velZ = evento.values[2]
            // velocidad angular en rad/s sobre cada eje
        }
        override fun onAccuracyChanged(sensor: Sensor, precision: Int) {}
    }

    private fun activarGiroscopio() {
        val sensor = gestorSensores.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gestorSensores.registerListener(listenerGiroscopio, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun desactivarGiroscopio() {
        gestorSensores.unregisterListener(listenerGiroscopio)
    }

    // Funcionalidad: magnetometro
    private val listenerMagnetometro = object : SensorEventListener {
        override fun onSensorChanged(evento: SensorEvent) {
            val campoX = evento.values[0]
            val campoY = evento.values[1]
            val campoZ = evento.values[2]
            // campo magnético en µT sobre cada eje
        }
        override fun onAccuracyChanged(sensor: Sensor, precision: Int) {}
    }

    private fun activarMagnetometro() {
        val sensor = gestorSensores.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gestorSensores.registerListener(listenerMagnetometro, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun desactivarMagnetometro() {
        gestorSensores.unregisterListener(listenerMagnetometro)
    }

    private val listenerPodometro = object : SensorEventListener {
        override fun onSensorChanged(evento: SensorEvent) {
            val pasosDesdeReinicio = evento.values[0]
            // pasos acumulados desde el último reinicio del teléfono
        }
        override fun onAccuracyChanged(sensor: Sensor, precision: Int) {}
    }

    private fun pedirPodometro() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            activarPodometro()
        } else {
            lanzadorPermisoPodometro.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun activarPodometro() {
        val sensor = gestorSensores.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        gestorSensores.registerListener(listenerPodometro, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun desactivarPodometro() {
        gestorSensores.unregisterListener(listenerPodometro)
    }

    // Funcionalidad: sensor-luz
    private val listenerSensorLuz = object : SensorEventListener {
        override fun onSensorChanged(evento: SensorEvent) {
            val lux = evento.values[0]
            // iluminación ambiente en lux
        }
        override fun onAccuracyChanged(sensor: Sensor, precision: Int) {}
    }

    private fun activarSensorLuz() {
        val sensor = gestorSensores.getDefaultSensor(Sensor.TYPE_LIGHT)
        gestorSensores.registerListener(listenerSensorLuz, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun desactivarSensorLuz() {
        gestorSensores.unregisterListener(listenerSensorLuz)
    }

    // Funcionalidad: sensor-proximidad
    private val listenerSensorProximidad = object : SensorEventListener {
        override fun onSensorChanged(evento: SensorEvent) {
            val distanciaCm = evento.values[0]
            // en muchos móviles es binario: 0 (cerca) o el máximo del sensor (lejos)
        }
        override fun onAccuracyChanged(sensor: Sensor, precision: Int) {}
    }

    private fun activarSensorProximidad() {
        val sensor = gestorSensores.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        gestorSensores.registerListener(listenerSensorProximidad, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun desactivarSensorProximidad() {
        gestorSensores.unregisterListener(listenerSensorProximidad)
    }

    // Funcionalidad: barometro
    private val listenerBarometro = object : SensorEventListener {
        override fun onSensorChanged(evento: SensorEvent) {
            val presionHpa = evento.values[0]
            // presión atmosférica en hPa (milibares)
        }
        override fun onAccuracyChanged(sensor: Sensor, precision: Int) {}
    }

    private fun activarBarometro() {
        val sensor = gestorSensores.getDefaultSensor(Sensor.TYPE_PRESSURE)
        gestorSensores.registerListener(listenerBarometro, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun desactivarBarometro() {
        gestorSensores.unregisterListener(listenerBarometro)
    }

    private fun pedirUbicacionActual() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            obtenerUbicacionActual()
        } else {
            lanzadorPermisoUbicacion.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun obtenerUbicacionActual(): Location? {
        val gestor = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (proveedor in gestor.getProviders(true)) {
            val ubicacion = gestor.getLastKnownLocation(proveedor)
            if (ubicacion != null) return ubicacion
        }
        return null
    }

    private fun pedirHuella() {
        val gestor = BiometricManager.from(this)
        if (gestor.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Autenticación biométrica no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verifica tu identidad")
            .setNegativeButtonText("Cancelar")
            .build()
        promptHuella.authenticate(info)
    }

    private fun reproducirAudio(rutaOUrl: String) {
        detenerAudio()
        reproductorAudio = MediaPlayer().apply {
            setDataSource(rutaOUrl)
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }

    private fun detenerAudio() {
        reproductorAudio?.release()
        reproductorAudio = null
    }

    // Funcionalidad: reproducir-video
    // Añade un VideoView a tu layout y pásaselo aquí junto con la URL/ruta.
    private fun reproducirVideo(videoView: VideoView, rutaOUrl: String) {
        videoView.setMediaController(MediaController(this))
        videoView.setVideoURI(Uri.parse(rutaOUrl))
        videoView.start()
    }

    private fun pedirBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permisos = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            val faltaAlguno = permisos.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (faltaAlguno) {
                lanzadorPermisosBluetooth.launch(permisos)
            } else {
                iniciarBusquedaBluetooth()
            }
        } else {
            iniciarBusquedaBluetooth()
        }
    }

    @SuppressLint("MissingPermission")
    private fun iniciarBusquedaBluetooth() {
        val gestor = getSystemService(BluetoothManager::class.java)
        val adaptador = gestor?.adapter ?: return
        if (adaptador.isEnabled) adaptador.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun detenerBusquedaBluetooth() {
        getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
    }

    // Nota: lo suyo es activar/desactivar el modo lector en onResume()/onPause(),
    // no dejarlo siempre encendido desde onCreate().
    private fun activarLecturaNfc() {
        adaptadorNfc?.enableReaderMode(
            this,
            { tag: Tag ->
                // tag leída; usa tag.id o los datos que necesites aquí
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
            null
        )
    }

    private fun desactivarLecturaNfc() {
        adaptadorNfc?.disableReaderMode(this)
    }

    // Funcionalidad: compartir-texto
    private fun compartirTexto(texto: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, texto)
        }
        startActivity(Intent.createChooser(intent, null))
    }

    // Funcionalidad: compartir-archivos
    private fun compartirArchivo(archivo: File, tipoMime: String = "*/*") {
        val uri = FileProvider.getUriForFile(this, "com.ejemplo.tt.fileprovider", archivo)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = tipoMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, null))
    }

    // Funcionalidad: enviar-email
    private fun enviarEmail(destinatario: String, asunto: String = "", cuerpo: String = "") {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(destinatario))
            putExtra(Intent.EXTRA_SUBJECT, asunto)
            putExtra(Intent.EXTRA_TEXT, cuerpo)
        }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }

    // Funcionalidad: llamar-telefono
    private fun llamarTelefono(numero: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numero"))
        startActivity(intent)
    }

    private fun enviarSms(numero: String, mensaje: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            enviarSmsDirecto(numero, mensaje)
        } else {
            smsPendiente = numero to mensaje
            lanzadorPermisoSms.launch(Manifest.permission.SEND_SMS)
        }
    }

    private fun enviarSmsDirecto(numero: String, mensaje: String) {
        val gestor = getSystemService(SmsManager::class.java)
        gestor.sendTextMessage(numero, null, mensaje, null, null)
    }

    // Funcionalidad: copiar-portapapeles
    private fun copiarAlPortapapeles(texto: String, etiqueta: String = "texto") {
        val gestor = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        gestor.setPrimaryClip(ClipData.newPlainText(etiqueta, texto))
    }

    private fun pedirGuardarEnGaleria(bitmap: Bitmap, nombre: String = "imagen_${System.currentTimeMillis()}") {
        val necesitaPermiso = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (necesitaPermiso) {
            imagenPendienteGaleria = bitmap to nombre
            lanzadorPermisoGaleria.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            guardarImagenEnGaleria(bitmap, nombre)
        }
    }

    private fun guardarImagenEnGaleria(bitmap: Bitmap, nombre: String): Boolean {
        val valores = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores) ?: return false
        contentResolver.openOutputStream(uri)?.use { salida ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, salida)
        }
        return true
    }

    private fun pedirContactos() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            leerContactos()
        } else {
            lanzadorPermisoContactos.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun leerContactos(): List<String> {
        val nombres = mutableListOf<String>()
        val cursor = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null)
        cursor?.use {
            val idxNombre = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            while (it.moveToNext()) {
                nombres.add(it.getString(idxNombre))
            }
        }
        return nombres
    }

    private fun pedirCalendario() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            leerEventosCalendario()
        } else {
            lanzadorPermisoCalendario.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    private fun leerEventosCalendario(): List<String> {
        val titulos = mutableListOf<String>()
        val cursor = contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.TITLE),
            null, null, null
        )
        cursor?.use {
            val idxTitulo = it.getColumnIndex(CalendarContract.Events.TITLE)
            while (it.moveToNext()) {
                titulos.add(it.getString(idxTitulo))
            }
        }
        return titulos
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

    // Funcionalidad: workmanager (la clase TareaPeriodica está en el mismo paquete, no hace falta import)
    private fun programarTareaPeriodica() {
        val peticion = PeriodicWorkRequestBuilder<TareaPeriodica>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueue(peticion)
    }

    // Funcionalidad: servicio-foreground
    private fun iniciarServicioPrimerPlano() {
        val intent = Intent(this, ServicioPrimerPlano::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun detenerServicioPrimerPlano() {
        stopService(Intent(this, ServicioPrimerPlano::class.java))
    }

    // Funcionalidad: deep-links
    private fun manejarEnlaceEntrante() {
        val uri = intent?.data ?: return
        // la app se abrió con este enlace; mira uri.pathSegments, uri.getQueryParameter(...), etc.
    }

    // Funcionalidad: cambiar-idioma
    private fun cambiarIdioma(codigoIdioma: String) {
        // p.ej. codigoIdioma = "es" o "en"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(codigoIdioma))
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
