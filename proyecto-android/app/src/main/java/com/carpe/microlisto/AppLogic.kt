package com.carpe.microlisto

import android.content.Context
import com.carpe.microlisto.data.BaseDatos
import com.carpe.microlisto.debug.DebugLog

object AppLogic {

    fun onIniciar(context: Context) {
        // Intentar restaurar backup de BD si la BD interna está vacía.
        // Esto permite que al reinstalar la app tras desinstalar, el
        // historial se recupere automáticamente de /storage/emulated/0/Microlisto/
        try {
            val db = BaseDatos(context.applicationContext)
            val importado = db.importarBackupSiVacio()
            if (importado) {
                DebugLog.info("AppLogic", "Backup de BD importado al arrancar")
            }
        } catch (e: Exception) {
            DebugLog.warn("AppLogic", "Error intentando importar backup: ${e.message}")
        }
    }

    fun debeMostrarConfiguracion(context: Context): Boolean {
        return ConfiguracionInicialActivity.debeMostrarConfiguracion(context)
    }

    /**
     * Exporta la BD al almacenamiento público. Llamar cada vez que se
     * inserta/actualiza/borra una conversación para mantener el backup al día.
     */
    fun exportarBackup(context: Context) {
        try {
            val db = BaseDatos(context.applicationContext)
            db.exportarBackup()
        } catch (e: Exception) {
            DebugLog.warn("AppLogic", "Error exportando backup: ${e.message}")
        }
    }
}