package com.carpe.microlisto.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// SQLiteOpenHelper en vez de Room: cero dependencias/configuración extra.
// Tabla y columnas de ejemplo; adáptalas a lo que necesite tu app.
class BaseDatos(context: Context) : SQLiteOpenHelper(context, "datos_app.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE elementos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                nombre TEXT NOT NULL,
                valor TEXT
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, versionAntigua: Int, versionNueva: Int) {
        db.execSQL("DROP TABLE IF EXISTS elementos")
        onCreate(db)
    }

    fun insertar(nombre: String, valor: String): Long {
        val valores = ContentValues().apply {
            put("nombre", nombre)
            put("valor", valor)
        }
        return writableDatabase.insert("elementos", null, valores)
    }

    fun listarTodos(): List<Pair<String, String>> {
        val lista = mutableListOf<Pair<String, String>>()
        val cursor = readableDatabase.query("elementos", arrayOf("nombre", "valor"), null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                lista.add(it.getString(0) to it.getString(1))
            }
        }
        return lista
    }
}
