package com.vigia.adb

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.InputStream
import kotlin.coroutines.resume

/**
 * Shell vía Shizuku (https://shizuku.rikka.app). Shizuku arranca un servicio con identidad shell, y puede
 * iniciarse sin PC con la depuración inalámbrica de Android 11+ desde la propia app de Shizuku.
 */
class ShizukuShell(private val contexto: Context) : ShellProvider {
    override val nombre: String get() = "Shizuku"

    private fun instalado(): Boolean {
        return try {
            contexto.packageManager.getPackageInfo(PAQUETE_SHIZUKU, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun estado(): EstadoShell {
        return try {
            if (!Shizuku.pingBinder()) {
                if (instalado()) EstadoShell.NO_ACTIVO else EstadoShell.NO_INSTALADO
            } else if (Shizuku.isPreV11()) {
                EstadoShell.NO_DISPONIBLE
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                EstadoShell.LISTO
            } else {
                EstadoShell.SIN_PERMISO
            }
        } catch (e: Throwable) {
            if (instalado()) EstadoShell.NO_ACTIVO else EstadoShell.NO_INSTALADO
        }
    }

    /** Muestra el diálogo de Shizuku para autorizar esta app. Devuelve true si se concede. */
    suspend fun pedirPermiso(): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
        return suspendCancellableCoroutine { cont ->
            lateinit var escucha: Shizuku.OnRequestPermissionResultListener
            escucha = Shizuku.OnRequestPermissionResultListener { codigo, resultado ->
                if (codigo == CODIGO_PERMISO) {
                    Shizuku.removeRequestPermissionResultListener(escucha)
                    if (cont.isActive) cont.resume(resultado == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(escucha)
            cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(escucha) }
            try {
                Shizuku.requestPermission(CODIGO_PERMISO)
            } catch (e: Throwable) {
                Shizuku.removeRequestPermissionResultListener(escucha)
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /**
     * Shizuku 13.1.x está retirando la API pública `newProcess` en favor de UserService (que exige AIDL).
     * Para ejecutar comandos de una línea sigue estando disponible por reflexión, que es lo que hacen las apps
     * que lo usan. Si una versión futura la quitara, esta función fallará con un mensaje claro.
     */
    private fun nuevoProceso(orden: Array<String>): Process {
        try {
            val metodo = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            metodo.isAccessible = true
            return metodo.invoke(null, orden, null, null) as Process
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException("Esta versión de Shizuku ya no permite ejecutar comandos así", e)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw IllegalStateException("Shizuku rechazó el comando: " + (e.targetException?.message ?: "sin detalle"), e)
        }
    }

    override suspend fun ejecutar(comando: String, timeoutMs: Long): ResultadoShell = withContext(Dispatchers.IO) {
        if (estado() != EstadoShell.LISTO) throw IllegalStateException("Shizuku no está listo")
        val proceso = nuevoProceso(arrayOf("sh", "-c", comando))
        val vigilante = Thread {
            try {
                Thread.sleep(timeoutMs)
                proceso.destroy()
            } catch (e: InterruptedException) {
            }
        }
        vigilante.isDaemon = true
        vigilante.start()
        try {
            coroutineScope {
                val salida = async(Dispatchers.IO) { leer(proceso.inputStream) }
                val error = async(Dispatchers.IO) { leer(proceso.errorStream) }
                val codigo = try {
                    proceso.waitFor()
                } catch (e: InterruptedException) {
                    -1
                }
                ResultadoShell(codigo, salida.await(), error.await())
            }
        } finally {
            vigilante.interrupt()
            try {
                proceso.destroy()
            } catch (e: Exception) {
            }
        }
    }

    private fun leer(entrada: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = entrada.read(buf)
            if (n < 0) break
            if (bytes.size() < LIMITE) bytes.write(buf, 0, n)
        }
        return bytes.toString("UTF-8")
    }

    companion object {
        const val PAQUETE_SHIZUKU = "moe.shizuku.privileged.api"
        private const val CODIGO_PERMISO = 4242
        private const val LIMITE = 2 * 1024 * 1024
    }
}
