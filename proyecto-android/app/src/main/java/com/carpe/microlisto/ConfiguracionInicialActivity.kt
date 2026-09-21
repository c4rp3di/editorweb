package com.carpe.microlisto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.carpe.microlisto.databinding.ActivityConfiguracionInicialBinding

class ConfiguracionInicialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfiguracionInicialBinding
    private val pasosCompletados = mutableSetOf<String>()

    private val lanzadorPermisoAudio =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) {
                pasosCompletados.add(PASO_AUDIO)
                binding.botonPermisoAudio.isEnabled = false
                binding.botonPermisoAudio.text = getString(R.string.config_boton_ya_lo_hice)
                comprobarTodosLosPasos()
            } else {
                Toast.makeText(this, R.string.permiso_denegado, Toast.LENGTH_SHORT).show()
            }
        }

    private val lanzadorPermisoNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            if (concedido) {
                pasosCompletados.add(PASO_NOTIFICACIONES)
                binding.botonPermisoNotificaciones.isEnabled = false
                binding.botonPermisoNotificaciones.text = getString(R.string.config_boton_ya_lo_hice)
                comprobarTodosLosPasos()
            } else {
                Toast.makeText(this, R.string.permiso_denegado, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfiguracionInicialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarBotones()
        actualizarEstadoInicial()
    }

    private fun configurarBotones() {
        binding.botonPermisoAudio.setOnClickListener {
            lanzadorPermisoAudio.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.botonPermisoNotificaciones.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lanzadorPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.botonOptimizacionBateria.setOnClickListener {
            pedirOptimizacionBateria()
        }

        binding.botonAutostart.setOnClickListener {
            abrirAutostartHyperOS()
        }

        binding.botonCandadoRecientes.setOnClickListener {
            mostrarInstruccionesCandado()
        }

        binding.botonAccesoArchivos.setOnClickListener {
            pedirAccesoTotalArchivos()
        }

        binding.botonContinuar.setOnClickListener {
            if (esTodoObligatorioCompletado()) {
                marcarConfiguracionCompletada()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, R.string.requiere_permisos_obligatorios, Toast.LENGTH_SHORT).show()
            }
        }

        binding.botonOmitir.setOnClickListener {
            if (tienePermisoAudio() && tienePermisoNotificaciones()) {
                marcarConfiguracionCompletada()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, R.string.requiere_permisos_obligatorios, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun actualizarEstadoInicial() {
        if (tienePermisoAudio()) {
            pasosCompletados.add(PASO_AUDIO)
            binding.botonPermisoAudio.isEnabled = false
            binding.botonPermisoAudio.text = getString(R.string.config_boton_ya_lo_hice)
        }

        if (tienePermisoNotificaciones()) {
            pasosCompletados.add(PASO_NOTIFICACIONES)
            binding.botonPermisoNotificaciones.isEnabled = false
            binding.botonPermisoNotificaciones.text = getString(R.string.config_boton_ya_lo_hice)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            pasosCompletados.add(PASO_NOTIFICACIONES)
            binding.botonPermisoNotificaciones.isEnabled = false
            binding.botonPermisoNotificaciones.text = getString(R.string.config_boton_ya_lo_hice)
        }

        if (estaOptimizacionBateriaDesactivada()) {
            pasosCompletados.add(PASO_BATERIA)
            binding.botonOptimizacionBateria.isEnabled = false
            binding.botonOptimizacionBateria.text = getString(R.string.config_boton_ya_lo_hice)
        }

        if (tieneAccesoTotalArchivos()) {
            pasosCompletados.add(PASO_ARCHIVOS)
            binding.botonAccesoArchivos.isEnabled = false
            binding.botonAccesoArchivos.text = getString(R.string.config_boton_ya_lo_hice)
        }

        comprobarTodosLosPasos()
    }

    private fun tienePermisoAudio(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun tienePermisoNotificaciones(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun estaOptimizacionBateriaDesactivada(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun tieneAccesoTotalArchivos(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return false
    }

    private fun pedirOptimizacionBateria() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun abrirAutostartHyperOS() {
        val intentos = listOf(
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        )

        for (componente in intentos) {
            try {
                val intent = Intent().apply {
                    setClassName(componente.substringBeforeLast('.'), componente)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                pasosCompletados.add(PASO_AUTOSTART)
                binding.botonAutostart.isEnabled = false
                binding.botonAutostart.text = getString(R.string.config_boton_ya_lo_hice)
                comprobarTodosLosPasos()
                return
            } catch (e: Exception) {
                // Probar el siguiente
            }
        }

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            Toast.makeText(this, "Busca 'Autostart' en esta pantalla", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir los ajustes de la app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun mostrarInstruccionesCandado() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.config_paso_5_titulo)
        builder.setMessage(R.string.config_instrucciones_candado)
        builder.setPositiveButton(R.string.config_boton_ya_lo_hice) { _, _ ->
            pasosCompletados.add(PASO_CANDADO)
            binding.botonCandadoRecientes.isEnabled = false
            binding.botonCandadoRecientes.text = getString(R.string.config_boton_ya_lo_hice)
            comprobarTodosLosPasos()
        }
        builder.setNegativeButton(R.string.cancelar, null)
        builder.show()
    }

    private fun pedirAccesoTotalArchivos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private fun esTodoObligatorioCompletado(): Boolean {
        return pasosCompletados.contains(PASO_AUDIO) &&
                pasosCompletados.contains(PASO_NOTIFICACIONES)
    }

    private fun comprobarTodosLosPasos() {
        val todos = pasosCompletados.contains(PASO_AUDIO) &&
                pasosCompletados.contains(PASO_NOTIFICACIONES) &&
                pasosCompletados.contains(PASO_BATERIA) &&
                pasosCompletados.contains(PASO_AUTOSTART) &&
                pasosCompletados.contains(PASO_CANDADO) &&
                pasosCompletados.contains(PASO_ARCHIVOS)

        binding.botonContinuar.isEnabled = esTodoObligatorioCompletado()
        binding.textoEstado.text = if (todos) {
            getString(R.string.config_completado)
        } else {
            "Pasos obligatorios: ${pasosCompletados.count { it == PASO_AUDIO || it == PASO_NOTIFICACIONES }}/2"
        }
    }

    private fun marcarConfiguracionCompletada() {
        val prefs = getSharedPreferences("microlisto_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("configuracion_inicial_completada", true).apply()
    }

    override fun onResume() {
        super.onResume()
        if (estaOptimizacionBateriaDesactivada() && !pasosCompletados.contains(PASO_BATERIA)) {
            pasosCompletados.add(PASO_BATERIA)
            binding.botonOptimizacionBateria.isEnabled = false
            binding.botonOptimizacionBateria.text = getString(R.string.config_boton_ya_lo_hice)
        }
        if (tieneAccesoTotalArchivos() && !pasosCompletados.contains(PASO_ARCHIVOS)) {
            pasosCompletados.add(PASO_ARCHIVOS)
            binding.botonAccesoArchivos.isEnabled = false
            binding.botonAccesoArchivos.text = getString(R.string.config_boton_ya_lo_hice)
        }
        comprobarTodosLosPasos()
    }

    companion object {
        const val PASO_AUDIO = "audio"
        const val PASO_NOTIFICACIONES = "notificaciones"
        const val PASO_BATERIA = "bateria"
        const val PASO_AUTOSTART = "autostart"
        const val PASO_CANDADO = "candado"
        const val PASO_ARCHIVOS = "archivos"

        fun debeMostrarConfiguracion(context: Context): Boolean {
            val prefs = context.getSharedPreferences("microlisto_prefs", Context.MODE_PRIVATE)
            return !prefs.getBoolean("configuracion_inicial_completada", false)
        }
    }
}