package com.carpe.microlisto.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.carpe.microlisto.debug.DebugLog
import java.io.File

data class Estadisticas(
    val numConversaciones: Int,
    val duracionTotalMs: Long,
    val espacioWavsBytes: Long
)

class BaseDatos(context: Context) :
    SQLiteOpenHelper(context, NOMBRE_DB, null, VERSION_DB) {

    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE conversaciones (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                titulo TEXT NOT NULL,
                fecha_ms INTEGER NOT NULL,
                duracion_ms INTEGER NOT NULL,
                num_hablantes INTEGER NOT NULL DEFAULT 0,
                ruta_audio TEXT NOT NULL,
                transcripcion TEXT NOT NULL DEFAULT '',
                resumen TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE segmentos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                id_conversacion INTEGER NOT NULL,
                hablante_id INTEGER NOT NULL,
                inicio_ms INTEGER NOT NULL,
                fin_ms INTEGER NOT NULL,
                texto TEXT NOT NULL DEFAULT '',
                confianza REAL NOT NULL DEFAULT 1.0,
                FOREIGN KEY (id_conversacion) REFERENCES conversaciones(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_segmentos_conv ON segmentos(id_conversacion)")
        db.execSQL("CREATE INDEX idx_conversaciones_fecha ON conversaciones(fecha_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, versionAntigua: Int, versionNueva: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    // ==== Backup / Restore ====

    /**
     * Copia la BD interna a /storage/emulated/0/Microlisto/microlisto.db.
     * Sobrevive a desinstalaciones de la app.
     */
    fun exportarBackup(): Boolean {
        return try {
            if (!RutasPublicas.hayAcceso()) {
                DebugLog.warn("BD", "Sin acceso a carpeta pública para backup")
                return false
            }
            val origen = File(appContext.getDatabasePath(NOMBRE_DB).absolutePath)
            if (!origen.exists()) {
                DebugLog.warn("BD", "No existe la BD interna para exportar")
                return false
            }
            // Asegurar que los cambios están en disco
            writableDatabase.beginTransaction()
            try { writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() }
            close()

            val destino = RutasPublicas.archivoBackupBD()
            origen.copyTo(destino, overwrite = true)
            DebugLog.info("BD", "Backup exportado: ${destino.length()} bytes")
            true
        } catch (e: Exception) {
            DebugLog.error("BD", "Error exportando backup: ${e.message}")
            false
        }
    }

    /**
     * Si la BD interna está vacía y hay un backup en la carpeta pública,
     * lo importa. Llamar al arrancar la app.
     * @return true si se importó algo.
     */
    fun importarBackupSiVacio(): Boolean {
        return try {
            if (!RutasPublicas.hayAcceso()) return false
            val backup = RutasPublicas.archivoBackupBD()
            if (!backup.exists() || backup.length() < 1000) return false

            // Comprobar si la BD interna está vacía
            var numConv = 0
            val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM conversaciones", null)
            cursor.use { if (it.moveToFirst()) numConv = it.getInt(0) }
            if (numConv > 0) {
                DebugLog.info("BD", "BD interna ya tiene $numConv conversaciones, no se importa backup")
                return false
            }

            // Cerrar la BD antes de sobreescribirla
            close()

            val destino = File(appContext.getDatabasePath(NOMBRE_DB).absolutePath)
            backup.copyTo(destino, overwrite = true)
            DebugLog.info("BD", "Backup importado: ${backup.length()} bytes")
            true
        } catch (e: Exception) {
            DebugLog.error("BD", "Error importando backup: ${e.message}")
            false
        }
    }

    // ==== Conversaciones ====

    fun insertarConversacion(c: Conversacion): Long {
        val valores = ContentValues().apply {
            put("titulo", c.titulo)
            put("fecha_ms", c.fechaMs)
            put("duracion_ms", c.duracionMs)
            put("num_hablantes", c.numHablantes)
            put("ruta_audio", c.rutaAudio)
            put("transcripcion", c.transcripcion)
            put("resumen", c.resumen)
        }
        return writableDatabase.insert("conversaciones", null, valores)
    }

    fun actualizarTitulo(id: Long, nuevoTitulo: String) {
        val valores = ContentValues().apply { put("titulo", nuevoTitulo) }
        writableDatabase.update("conversaciones", valores, "id = ?", arrayOf(id.toString()))
    }

    fun actualizarNumHablantes(id: Long, n: Int) {
        val valores = ContentValues().apply { put("num_hablantes", n) }
        writableDatabase.update("conversaciones", valores, "id = ?", arrayOf(id.toString()))
    }

    fun actualizarTranscripcion(id: Long, texto: String) {
        val valores = ContentValues().apply { put("transcripcion", texto) }
        writableDatabase.update("conversaciones", valores, "id = ?", arrayOf(id.toString()))
    }

    fun actualizarResumen(id: Long, resumen: String) {
        val valores = ContentValues().apply { put("resumen", resumen) }
        writableDatabase.update("conversaciones", valores, "id = ?", arrayOf(id.toString()))
    }

    fun listarConversaciones(): List<Conversacion> {
        val lista = mutableListOf<Conversacion>()
        val cursor = readableDatabase.query(
            "conversaciones", null, null, null, null, null, "fecha_ms DESC"
        )
        cursor.use { while (it.moveToNext()) lista.add(cursorAConversacion(it)) }
        return lista
    }

    fun buscarConversaciones(texto: String): List<Conversacion> {
        if (texto.isBlank()) return listarConversaciones()
        val lista = mutableListOf<Conversacion>()
        val patron = "%$texto%"
        val cursor = readableDatabase.query(
            "conversaciones", null,
            "transcripcion LIKE ? OR titulo LIKE ?",
            arrayOf(patron, patron),
            null, null, "fecha_ms DESC"
        )
        cursor.use { while (it.moveToNext()) lista.add(cursorAConversacion(it)) }
        return lista
    }

    fun obtenerConversacion(id: Long): Conversacion? {
        val cursor = readableDatabase.query(
            "conversaciones", null, "id = ?", arrayOf(id.toString()), null, null, null, "1"
        )
        cursor.use { if (it.moveToFirst()) return cursorAConversacion(it) }
        return null
    }

    fun eliminarConversacion(id: Long) {
        writableDatabase.delete("conversaciones", "id = ?", arrayOf(id.toString()))
    }

    fun eliminarTodasLasConversaciones() {
        writableDatabase.delete("conversaciones", null, null)
        writableDatabase.delete("segmentos", null, null)
    }

    fun obtenerTodasLasRutasAudio(): List<String> {
        val rutas = mutableListOf<String>()
        val cursor = readableDatabase.query(
            "conversaciones", arrayOf("ruta_audio"), null, null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                val r = it.getString(0)
                if (!r.isNullOrBlank()) rutas.add(r)
            }
        }
        return rutas
    }

    fun obtenerEstadisticas(): Estadisticas {
        var numConv = 0
        var durTotal = 0L
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(duracion_ms), 0) FROM conversaciones",
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                numConv = it.getInt(0)
                durTotal = it.getLong(1)
            }
        }
        var espacio = 0L
        for (ruta in obtenerTodasLasRutasAudio()) {
            try {
                val f = File(ruta)
                if (f.exists()) espacio += f.length()
            } catch (_: Exception) {}
        }
        return Estadisticas(numConv, durTotal, espacio)
    }

    fun insertarSegmentos(idConversacion: Long, segmentos: List<Segmento>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in segmentos) {
                val valores = ContentValues().apply {
                    put("id_conversacion", idConversacion)
                    put("hablante_id", s.hablanteId)
                    put("inicio_ms", s.inicioMs)
                    put("fin_ms", s.finMs)
                    put("texto", s.texto)
                    put("confianza", s.confianza)
                }
                db.insert("segmentos", null, valores)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun eliminarSegmentosDeConversacion(idConversacion: Long) {
        writableDatabase.delete("segmentos", "id_conversacion = ?", arrayOf(idConversacion.toString()))
    }

    fun listarSegmentos(idConversacion: Long): List<Segmento> {
        val lista = mutableListOf<Segmento>()
        val cursor = readableDatabase.query(
            "segmentos", null, "id_conversacion = ?",
            arrayOf(idConversacion.toString()),
            null, null, "inicio_ms ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                lista.add(
                    Segmento(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        idConversacion = it.getLong(it.getColumnIndexOrThrow("id_conversacion")),
                        hablanteId = it.getInt(it.getColumnIndexOrThrow("hablante_id")),
                        inicioMs = it.getLong(it.getColumnIndexOrThrow("inicio_ms")),
                        finMs = it.getLong(it.getColumnIndexOrThrow("fin_ms")),
                        texto = it.getString(it.getColumnIndexOrThrow("texto")) ?: "",
                        confianza = it.getFloat(it.getColumnIndexOrThrow("confianza"))
                    )
                )
            }
        }
        return lista
    }

    private fun cursorAConversacion(cursor: android.database.Cursor): Conversacion {
        return Conversacion(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            titulo = cursor.getString(cursor.getColumnIndexOrThrow("titulo")) ?: "",
            fechaMs = cursor.getLong(cursor.getColumnIndexOrThrow("fecha_ms")),
            duracionMs = cursor.getLong(cursor.getColumnIndexOrThrow("duracion_ms")),
            numHablantes = cursor.getInt(cursor.getColumnIndexOrThrow("num_hablantes")),
            rutaAudio = cursor.getString(cursor.getColumnIndexOrThrow("ruta_audio")) ?: "",
            transcripcion = cursor.getString(cursor.getColumnIndexOrThrow("transcripcion")) ?: "",
            resumen = cursor.getString(cursor.getColumnIndexOrThrow("resumen")) ?: ""
        )
    }

    companion object {
        private const val NOMBRE_DB = "microlisto.db"
        private const val VERSION_DB = 1
    }
}