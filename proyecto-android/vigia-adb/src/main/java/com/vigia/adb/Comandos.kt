package com.vigia.adb

/**
 * Operaciones de shell que usan varias apps de la suite. Todo argumento que llega al shell se valida antes: nunca
 * se concatena texto libre dentro de un comando.
 */
class Comandos(private val shell: ShellProvider) {

    class ErrorComando(mensaje: String) : Exception(mensaje)

    private fun paqueteValido(p: String): String {
        if (!Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(p)) throw ErrorComando("Nombre de paquete no válido: " + p)
        return p
    }

    private fun permisoValido(p: String): String {
        if (!Regex("^[A-Za-z0-9_.]+$").matches(p)) throw ErrorComando("Nombre de permiso no válido: " + p)
        return p
    }

    private suspend fun lanzar(comando: String, timeoutMs: Long = 15000L): ResultadoShell = shell.ejecutar(comando, timeoutMs)

    /** Identidad con la que corren los comandos: «uid=2000(shell)» con ADB, «uid=0(root)» con root. */
    suspend fun identidad(): String = lanzar("id").salida.trim()

    /** Detiene el paquete y todos sus procesos, también el de apps de sistema (`am force-stop`). */
    suspend fun forzarParada(paquete: String): Boolean = lanzar("am force-stop " + paqueteValido(paquete)).ok

    suspend fun revocarPermiso(paquete: String, permiso: String): ResultadoShell {
        return lanzar("pm revoke " + paqueteValido(paquete) + " " + permisoValido(permiso))
    }

    suspend fun concederPermiso(paquete: String, permiso: String): ResultadoShell {
        return lanzar("pm grant " + paqueteValido(paquete) + " " + permisoValido(permiso))
    }

    suspend fun permisosEjecucion(paquete: String): Map<String, Boolean> {
        val r = lanzar("dumpsys package " + paqueteValido(paquete), 30000L)
        return ParserShell.permisosEjecucion(r.salida)
    }

    suspend fun appOps(paquete: String): List<OperacionApp> {
        val r = lanzar("cmd appops get " + paqueteValido(paquete))
        return ParserShell.appOps(r.salida)
    }

    /** Pone una operación de AppOps en «allow», «deny», «ignore» o «default». */
    suspend fun fijarAppOp(paquete: String, operacion: String, modo: String): ResultadoShell {
        if (!Regex("^[A-Z_0-9]+$").matches(operacion)) throw ErrorComando("Operación no válida: " + operacion)
        if (modo != "allow" && modo != "deny" && modo != "ignore" && modo != "default") throw ErrorComando("Modo no válido: " + modo)
        return lanzar("cmd appops set " + paqueteValido(paquete) + " " + operacion + " " + modo)
    }

    suspend fun procesos(): List<ProcesoInfo> {
        return ParserShell.procesos(lanzar("ps -A -o PID,USER,RSS,NAME").salida)
    }

    suspend fun bateria(): Map<String, String> = ParserShell.bateria(lanzar("dumpsys battery").salida)
}
