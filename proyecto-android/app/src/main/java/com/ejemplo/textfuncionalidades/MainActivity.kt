package com.ejemplo.textfuncionalidades

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.ejemplo.textfuncionalidades.ui.PantallaTest
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
import com.ejemplo.textfuncionalidades.data.BaseDatos
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.media.MediaRecorder
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
import java.net.HttpURLConnection
import java.net.URL
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.WindowManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
    // Funcionalidad: base-datos
    private lateinit var baseDatos: BaseDatos
    // Funcionalidad: camara
    private val lanzadorPermisoCamara = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) tomarFoto() else Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
    }
    private val lanzadorFotoCamara = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        // bitmap contiene la foto en miniatura; guárdala o muéstrala aquí
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
    // Funcionalidad: notificaciones
    private val lanzadorPermisoNotificaciones = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Funcionalidad: acelerometro / brujula
    private lateinit var gestorSensores: SensorManager
    // Funcionalidad: ubicacion
    private val lanzadorPermisoUbicacion = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) obtenerUbicacionActual() else Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_SHORT).show()
    }
    // Funcionalidad: huella
    private lateinit var promptHuella: BiometricPrompt
// [PROPIEDADES:FUNCIONALIDADES]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Funcionalidad: modo-oscuro
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        prefs = getSharedPreferences("datos_app", MODE_PRIVATE)
        baseDatos = BaseDatos(this)
        crearCanalNotificaciones()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            lanzadorPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Funcionalidad: pantalla-encendida
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gestorSensores = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Funcionalidad: huella (inicialización diferida; no puede hacerse en el constructor)
        promptHuella = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(resultado: BiometricPrompt.AuthenticationResult) {
                // autenticación correcta
            }
            override fun onAuthenticationError(codigoError: Int, mensaje: CharSequence) {
                Toast.makeText(this@MainActivity, "Error: $mensaje", Toast.LENGTH_SHORT).show()
            }
        })
// [ONCREATE:FUNCIONALIDADES]
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) mostrarPantalla(PantallaTest())

        drawerLayout = findViewById(R.id.drawerLayout)
        findViewById<NavigationView>(R.id.navigationView).setNavigationItemSelectedListener {
            drawerLayout.closeDrawers()
            true
        }
        configurarBarraInferior()
// [ONCREATE_FIN:FUNCIONALIDADES]
    }

    // Funcionalidad: varias-pantallas
    fun mostrarPantalla(pantalla: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contenedorPantallas, pantalla)
            .commit()
    }

    fun abrirMenuLateral() {
        drawerLayout.openDrawer(GravityCompat.START)
    }

    // Funcionalidad: barra-inferior
    fun configurarBarraInferior() {
        findViewById<BottomNavigationView>(R.id.barraInferior).setOnItemSelectedListener { item ->
            // if (item.itemId == R.id.navInicio) mostrarPantalla(PantallaEjemplo())
            true
        }
    }

    fun elegirCarpeta() {
        lanzadorSaf.launch(null)
    }

    fun listarArchivosDeCarpeta(): List<DocumentFile> {
        val carpeta = carpetaSaf ?: return emptyList()
        val documento = DocumentFile.fromTreeUri(this, carpeta) ?: return emptyList()
        return documento.listFiles().toList()
    }

    fun escribirArchivoEnCarpeta(nombre: String, contenido: String) {
        val carpeta = carpetaSaf ?: return
        val documento = DocumentFile.fromTreeUri(this, carpeta) ?: return
        val archivo = documento.findFile(nombre) ?: documento.createFile("text/plain", nombre) ?: return
        contentResolver.openOutputStream(archivo.uri)?.use { it.write(contenido.toByteArray()) }
    }

    fun leerArchivoDeCarpeta(nombre: String): String? {
        val carpeta = carpetaSaf ?: return null
        val documento = DocumentFile.fromTreeUri(this, carpeta) ?: return null
        val archivo = documento.findFile(nombre) ?: return null
        return contentResolver.openInputStream(archivo.uri)?.bufferedReader()?.use { it.readText() }
    }

    fun guardarPreferencia(clave: String, valor: String) {
        prefs.edit().putString(clave, valor).apply()
    }

    fun leerPreferencia(clave: String, porDefecto: String = ""): String {
        return prefs.getString(clave, porDefecto) ?: porDefecto
    }

    fun pedirFotoConCamara() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            tomarFoto()
        } else {
            lanzadorPermisoCamara.launch(Manifest.permission.CAMERA)
        }
    }

    fun tomarFoto() {
        lanzadorFotoCamara.launch(null)
    }

    fun elegirImagenDeGaleria() {
        lanzadorGaleria.launch("image/*")
    }

    fun grabarAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            iniciarGrabacionAudio()
        } else {
            lanzadorPermisoMicrofono.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun iniciarGrabacionAudio() {
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

    fun detenerGrabacionAudio() {
        grabadorAudio?.apply { stop(); release() }
        grabadorAudio = null
    }

    fun crearCanalNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel("canal_app", "Notificaciones", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    fun mostrarNotificacion(titulo: String, mensaje: String) {
        val notificacion = NotificationCompat.Builder(this, "canal_app")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titulo)
            .setContentText(mensaje)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify(1, notificacion)
    }

    // Funcionalidad: vibracion
    fun vibrar(duracionMs: Long = 200) {
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

    // Funcionalidad: descargar
    fun descargarTexto(url: String, alTerminar: (String?) -> Unit) {
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

    // Funcionalidad: enviar
    fun enviarDatos(url: String, cuerpoJson: String, alTerminar: (Int) -> Unit) {
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
    fun hayConexionInternet(): Boolean {
        val gestor = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val red = gestor.activeNetwork ?: return false
        val capacidades = gestor.getNetworkCapabilities(red) ?: return false
        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // Funcionalidad: abrir-enlaces
    fun abrirEnlace(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
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

    fun activarBrujula() {
        val sensor = gestorSensores.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gestorSensores.registerListener(listenerBrujula, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun desactivarBrujula() {
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

    fun activarAcelerometro() {
        val sensor = gestorSensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gestorSensores.registerListener(listenerAcelerometro, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun desactivarAcelerometro() {
        gestorSensores.unregisterListener(listenerAcelerometro)
    }

    fun pedirUbicacionActual() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            obtenerUbicacionActual()
        } else {
            lanzadorPermisoUbicacion.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    fun obtenerUbicacionActual(): Location? {
        val gestor = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (proveedor in gestor.getProviders(true)) {
            val ubicacion = gestor.getLastKnownLocation(proveedor)
            if (ubicacion != null) return ubicacion
        }
        return null
    }

    fun pedirHuella() {
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

// [METODOS:FUNCIONALIDADES]
}