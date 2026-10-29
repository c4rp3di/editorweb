package com.vigia.adbwifi

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.vigia.adb.AdbTcpShell
import com.vigia.adb.EstadoShell
import com.vigia.adb.ProtocoloAdb
import com.vigia.adb.ResultadoShell
import com.vigia.adb.ShellProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Resultado de un intento de emparejar. */
class ResultadoEmparejar(val ok: Boolean, val mensaje: String)

/**
 * ADB propio sin Shizuku ni PC: la app se empareja con la depuración inalámbrica de este mismo teléfono
 * (Android 11+) usando libadb-android, y después abre el shell por esa conexión.
 *
 * Flujo: (1) la persona activa «Depuración inalámbrica» y abre «Emparejar con código»; (2) se envía el código
 * (6 dígitos) al puerto de emparejamiento; (3) desde entonces, cada vez que la depuración inalámbrica esté activa,
 * [conectar] descubre el puerto de conexión por mDNS y abre sesión sin volver a pedir código.
 */
class AdbInalambricoShell(contexto: Context) : ShellProvider {

    private val app = contexto.applicationContext
    private val gestor: GestorAdb by lazy { GestorAdb.obtener(app) }
    private val cerrojo = Mutex()
    private val lectores = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var conectado = false

    override val nombre: String get() = "ADB inalámbrico propio"

    fun soportado(): Boolean = Build.VERSION.SDK_INT >= 30

    private fun prefs() = app.getSharedPreferences("adbwifi", Context.MODE_PRIVATE)

    /** Verdadero si alguna vez se completó el emparejamiento con este teléfono. */
    var emparejado: Boolean
        get() = prefs().getBoolean("emparejado", false)
        private set(v) {
            prefs().edit().putBoolean("emparejado", v).apply()
        }

    override suspend fun estado(): EstadoShell = withContext(Dispatchers.IO) {
        if (!soportado()) return@withContext EstadoShell.NO_DISPONIBLE
        if (!emparejado) return@withContext EstadoShell.SIN_PERMISO
        cerrojo.withLock {
            if (conectado && probar()) {
                EstadoShell.LISTO
            } else if (conectarInterno()) {
                EstadoShell.LISTO
            } else {
                EstadoShell.NO_ACTIVO
            }
        }
    }

    /** Envía el código de emparejamiento al puerto que muestra el diálogo de Android. */
    suspend fun emparejar(puerto: Int, codigo: String): ResultadoEmparejar = withContext(Dispatchers.IO) {
        if (!soportado()) return@withContext ResultadoEmparejar(false, "Hace falta Android 11 o superior.")
        val limpio = codigo.trim()
        if (limpio.length != 6 || !limpio.all { it.isDigit() }) {
            return@withContext ResultadoEmparejar(false, "El código tiene 6 dígitos.")
        }
        try {
            val r: Any? = gestor.pair("127.0.0.1", puerto, limpio)
            val aceptado = r !is Boolean || r
            if (!aceptado) return@withContext ResultadoEmparejar(false, "Android rechazó el código o el puerto ya no está activo.")
            emparejado = true
            val c = cerrojo.withLock { conectarInterno() }
            if (c) {
                ResultadoEmparejar(true, "Emparejado y conectado.")
            } else {
                ResultadoEmparejar(true, "Emparejado. Cierra el diálogo y pulsa «Conectar» con la depuración inalámbrica activa.")
            }
        } catch (e: Exception) {
            ResultadoEmparejar(false, "No se pudo emparejar: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Verdadero si esta app puede activar y apagar la depuración inalámbrica por sí sola. */
    fun puedeActivarSola(): Boolean {
        return app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Con una sesión abierta, concede a esta app el permiso WRITE_SECURE_SETTINGS (se concede una sola vez y
     * persiste). Desde entonces [conectar] enciende la depuración inalámbrica si hace falta, y
     * [apagarDepuracionInalambrica] la apaga al terminar.
     */
    suspend fun concederActivacionAutomatica(): ResultadoShell {
        return ejecutar("pm grant " + app.packageName + " android.permission.WRITE_SECURE_SETTINGS", 15000L)
    }

    private fun fijarDepuracionInalambrica(activa: Boolean): Boolean {
        if (!puedeActivarSola()) return false
        return try {
            Settings.Global.putInt(app.contentResolver, "adb_wifi_enabled", if (activa) 1 else 0)
        } catch (e: Exception) {
            false
        }
    }

    /** Apaga la depuración inalámbrica (cierra la sesión). Devuelve false si no hay permiso para hacerlo. */
    suspend fun apagarDepuracionInalambrica(): Boolean = withContext(Dispatchers.IO) {
        conectado = false
        fijarDepuracionInalambrica(false)
    }

    /** Descubre el puerto de conexión y abre sesión. Devuelve true si el shell responde. */
    suspend fun conectar(): Boolean = withContext(Dispatchers.IO) { cerrojo.withLock { conectarInterno() } }

    private suspend fun conectarInterno(): Boolean {
        conectado = false
        if (!soportado() || !emparejado) return false
        var servicio = DescubridorMdns.buscar(app, DescubridorMdns.TIPO_CONEXION, 2500L)
        if (servicio == null && fijarDepuracionInalambrica(true)) {
            // La app tiene permiso para activarla ella sola: se enciende y se espera a que Android la anuncie.
            delay(1500L)
            servicio = DescubridorMdns.buscar(app, DescubridorMdns.TIPO_CONEXION, 8000L)
        }
        if (servicio == null) servicio = DescubridorMdns.buscar(app, DescubridorMdns.TIPO_CONEXION, 3500L)
        if (servicio == null) return false
        return try {
            val r: Any? = gestor.connect("127.0.0.1", servicio.puerto)
            val aceptado = r !is Boolean || r
            conectado = aceptado && probar()
            conectado
        } catch (e: Exception) {
            false
        }
    }

    /** Comprueba que la sesión está viva ejecutando un eco. */
    private fun probar(): Boolean {
        return try {
            val s = gestor.openStream("shell:echo vigia")
            val texto = s.openInputStream().bufferedReader().readText()
            try {
                s.close()
            } catch (e: Exception) {
            }
            texto.contains("vigia")
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun ejecutar(comando: String, timeoutMs: Long): ResultadoShell = cerrojo.withLock {
        withContext(Dispatchers.IO) {
            if (!conectado && !conectarInterno()) {
                throw IllegalStateException("ADB inalámbrico no conectado: activa la depuración inalámbrica")
            }
            try {
                correr(comando, timeoutMs)
            } catch (e: Exception) {
                // La sesión pudo caerse (cambio de red, depuración apagada): un reintento tras reconectar.
                conectado = false
                if (!conectarInterno()) throw e
                correr(comando, timeoutMs)
            }
        }
    }

    private suspend fun correr(comando: String, timeoutMs: Long): ResultadoShell {
        val envuelto = "{ " + comando + "\n} 2>&1; echo \"" + AdbTcpShell.MARCA + ":\$?\""
        val stream = gestor.openStream("shell:" + envuelto)
        val lector = lectores.async { leerTodo(stream.openInputStream()) }
        val texto = withTimeoutOrNull(timeoutMs) { lector.await() }
        try {
            stream.close()
        } catch (e: Exception) {
        }
        if (texto == null) {
            lector.cancel()
            throw IllegalStateException("El comando superó el tiempo límite")
        }
        return ProtocoloAdb.separarCodigo(texto)
    }

    private fun leerTodo(entrada: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = entrada.read(buf)
            if (n < 0) break
            if (bytes.size() < LIMITE) bytes.write(buf, 0, n)
        }
        return bytes.toString("UTF-8")
    }

    companion object {
        private const val LIMITE = 2 * 1024 * 1024
    }
}

/** Instancia única del proveedor (comparte clave y conexión entre pantallas y notificaciones). */
object AdbWifi {
    @Volatile
    private var instancia: AdbInalambricoShell? = null

    fun proveedor(contexto: Context): AdbInalambricoShell {
        val actual = instancia
        if (actual != null) return actual
        synchronized(this) {
            val doble = instancia
            if (doble != null) return doble
            val nuevo = AdbInalambricoShell(contexto.applicationContext)
            instancia = nuevo
            return nuevo
        }
    }
}
