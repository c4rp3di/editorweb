package com.vigia.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object Portapapeles {
    fun copiar(contexto: Context, texto: String) {
        val cm = contexto.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Vigía", texto))
    }
}
