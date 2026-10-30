package com.vigia.adblock

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.vigia.core.ui.ActividadBase
import com.vigia.core.ui.Dialogos
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas

/** Laboratorio de AntiAds: activar/desactivar técnicas, ver qué hacen y exportar el diagnóstico. */
class LaboratorioActivity : ActividadBase() {

    private lateinit var contadores: TextView
    private lateinit var registro: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() {
            pintar()
            ui.postDelayed(this, 2000L)
        }
    }

    private val importar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val texto = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            Reglas.parse(texto) // si no es válido, lanza y no se guarda
            Reglas.archivo(this).writeText(texto)
            ReglasEstado.recargar(this)
            Toast.makeText(this, "Reglas importadas", Toast.LENGTH_SHORT).show()
            Diagnostico.anotar("reglas", "importadas desde archivo")
        } catch (e: Exception) {
            Dialogos.informar(this, "Reglas no válidas", e.message ?: e.javaClass.simpleName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "Laboratorio"), Vistas.anchoCompleto(this, 4))
        raiz.addView(
            Vistas.texto(
                this,
                "Activa o desactiva cada técnica y prueba una web. Los cambios valen al momento (recarga la página). " +
                    "Necesitas el Modo Profundo con el certificado instalado, y un navegador que lo acepte (Chrome).",
                true
            ), Vistas.anchoCompleto(this, 12)
        )

        val tarjeta = Vistas.tarjeta(this)
        tarjeta.addView(Vistas.subtitulo(this, "Técnicas"), Vistas.anchoCompleto(this, 8))
        for (t in Laboratorio.TECNICAS) {
            val boton = Vistas.boton(this, etiqueta(t), TipoBoton.TONAL) {}
            boton.setOnClickListener {
                Laboratorio.poner(this, t.id, !Laboratorio.activa(this, t.id))
                boton.text = etiqueta(t)
            }
            tarjeta.addView(boton, Vistas.anchoCompleto(this, 2))
            tarjeta.addView(Vistas.texto(this, t.descripcion, true), Vistas.anchoCompleto(this, 10))
        }
        raiz.addView(tarjeta, Vistas.anchoCompleto(this, 12))

        val rc = Vistas.tarjeta(this)
        rc.addView(Vistas.subtitulo(this, "Reglas por sitio"), Vistas.anchoCompleto(this, 4))
        rc.addView(
            Vistas.texto(this, "Archivo JSON con patrones de peticiones y las técnicas de cada sitio. Puedes importar el tuyo.", true),
            Vistas.anchoCompleto(this, 8)
        )
        rc.addView(Vistas.boton(this, "Importar reglas (JSON)", TipoBoton.TONAL) { importar.launch(arrayOf("*/*")) }, Vistas.anchoCompleto(this, 8))
        rc.addView(Vistas.boton(this, "Volver a las reglas integradas", TipoBoton.TONAL) {
            Reglas.archivo(this).delete()
            ReglasEstado.recargar(this)
            Toast.makeText(this, "Reglas integradas restauradas", Toast.LENGTH_SHORT).show()
        }, Vistas.anchoCompleto(this, 0))
        raiz.addView(rc, Vistas.anchoCompleto(this, 12))

        val d = Vistas.tarjeta(this)
        d.addView(Vistas.subtitulo(this, "Qué ha hecho cada técnica"), Vistas.anchoCompleto(this, 8))
        contadores = Vistas.texto(this, "", true)
        d.addView(contadores, Vistas.anchoCompleto(this, 12))
        d.addView(Vistas.subtitulo(this, "Diagnóstico"), Vistas.anchoCompleto(this, 4))
        registro = TextView(this)
        registro.typeface = Typeface.MONOSPACE
        registro.textSize = 11f
        registro.setTextIsSelectable(true)
        registro.setTextColor(Vistas.color(this, R.color.vc_texto))
        d.addView(registro, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Copiar diagnóstico", TipoBoton.TONAL) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AntiAds", informe()))
            Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
        }, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Compartir diagnóstico", TipoBoton.TONAL) {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, informe())
            startActivity(Intent.createChooser(i, "Compartir diagnóstico"))
        }, Vistas.anchoCompleto(this, 8))
        d.addView(Vistas.boton(this, "Vaciar", TipoBoton.TONAL) {
            Diagnostico.vaciar()
            Laboratorio.reiniciarConteo()
            pintar()
        }, Vistas.anchoCompleto(this, 0))
        raiz.addView(d, Vistas.anchoCompleto(this, 12))

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    private fun etiqueta(t: Laboratorio.Tecnica) = (if (Laboratorio.activa(this, t.id)) "✓  " else "✗  ") + t.nombre

    /** Texto para pegar en una conversación: técnicas activas, contadores, estado del MitM y últimas líneas. */
    private fun informe(): String {
        val sb = StringBuilder("AntiAds · diagnóstico\n")
        sb.append("MitM: ").append(if (MitmEstado.activo) "activo" else "NO activo").append(" · descifradas ").append(MitmEstado.descifradas.get())
            .append(" · rechazos ").append(MitmEstado.rechazos.get()).append('\n')
        sb.append("Técnicas: ").append(Laboratorio.TECNICAS.joinToString(", ") { it.id + "=" + (if (Laboratorio.activa(this, it.id)) "sí" else "no") }).append('\n')
        sb.append("Conteo: ").append(Laboratorio.TECNICAS.joinToString(", ") { it.id + "=" + Laboratorio.veces(it.id) }).append("\n\n")
        sb.append(Diagnostico.texto(300))
        return sb.toString()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tic)
    }

    override fun onPause() {
        ui.removeCallbacks(tic)
        super.onPause()
    }

    private fun pintar() {
        contadores.text = (if (MitmEstado.activo) "Modo Profundo: activo · " else "Modo Profundo: NO activo · ") +
            MitmEstado.descifradas.get() + " conexiones descifradas\n" +
            Laboratorio.TECNICAS.joinToString("\n") { it.nombre + ": " + Laboratorio.veces(it.id) }
        registro.text = Diagnostico.texto(40).ifEmpty { "(sin registros todavía)" }
    }
}
