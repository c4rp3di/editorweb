package com.vigia.adb

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class EstadoShell {
    /** Este proveedor no puede usarse en este teléfono. */
    NO_DISPONIBLE,

    /** Falta instalar la app necesaria (Shizuku). */
    NO_INSTALADO,

    /** Instalado pero el servicio no está en marcha (hay que iniciarlo). */
    NO_ACTIVO,

    /** En marcha, pero la persona aún no ha autorizado a esta app. */
    SIN_PERMISO,

    /** Listo: se pueden ejecutar comandos con privilegios de shell. */
    LISTO
}

/** [codigo] es el código de salida del comando (0 = bien); -1 si no se pudo saber. */
class ResultadoShell(val codigo: Int, val salida: String, val error: String = "") {
    val ok: Boolean get() = codigo == 0
}

/** Una vía para ejecutar comandos con la identidad «shell» (la misma que usa `adb shell`). */
interface ShellProvider {
    val nombre: String

    suspend fun estado(): EstadoShell

    /** Ejecuta [comando] con `sh -c`. No lanza por fallos del comando (mira el código); sí por fallos de conexión. */
    suspend fun ejecutar(comando: String, timeoutMs: Long = 15000L): ResultadoShell
}

/** Elige el mejor proveedor disponible. Las apps de la suite solo hablan con este objeto. */
class ShellManager(contexto: Context) {
    val shizuku = ShizukuShell(contexto.applicationContext)
    val adbLocal = AdbTcpShell(contexto.applicationContext)

    val proveedores: List<ShellProvider> = listOf(shizuku, adbLocal)

    private val _activo = MutableStateFlow<ShellProvider?>(null)

    /** Último proveedor que se comprobó como listo (null = ninguno). */
    val activo: StateFlow<ShellProvider?> get() = _activo

    /** Vuelve a comprobar todos los proveedores, en orden, y se queda con el primero que esté listo. */
    suspend fun detectar(): ShellProvider? {
        for (p in proveedores) {
            val e = try {
                p.estado()
            } catch (ex: Exception) {
                EstadoShell.NO_DISPONIBLE
            }
            if (e == EstadoShell.LISTO) {
                _activo.value = p
                return p
            }
        }
        _activo.value = null
        return null
    }

    /** Ejecuta con el proveedor activo; null si no hay ninguno listo. */
    suspend fun ejecutar(comando: String, timeoutMs: Long = 15000L): ResultadoShell? {
        val p = _activo.value ?: detectar() ?: return null
        return try {
            p.ejecutar(comando, timeoutMs)
        } catch (e: Exception) {
            _activo.value = null
            null
        }
    }
}
