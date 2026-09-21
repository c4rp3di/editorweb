package com.carpe.camara.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.carpe.camara.R
import com.carpe.camara.data.CamaraController
import com.carpe.camara.data.CamaraEstado
import com.carpe.camara.data.LenteFisica
import com.carpe.camara.data.Temporizador

class PanelAjustes(
    private val vista: View,
    private val controller: CamaraController,
    private val cicloDeVida: LifecycleOwner,
    private val vistaPrevia: PreviewView,
    private val onCambio: () -> Unit,
    private val onCerrar: () -> Unit
) {

    fun configurar() {
        val estado = controller.estado

        vista.findViewById<TextView>(R.id.txtAvisoLente).text =
            if (controller.seleccionLenteRealDisponible) "✓ 3 lentes" else "zoom→lente"

        vista.findViewById<Button>(R.id.btnDiagnostico).setOnClickListener { mostrarMenuDiagnostico() }
        vista.findViewById<Button>(R.id.btnDebug).setOnClickListener { mostrarLog() }

        val bP = vista.findViewById<Button>(R.id.btnLentePrincipal)
        val bT = vista.findViewById<Button>(R.id.btnLenteTele)
        val bU = vista.findViewById<Button>(R.id.btnLenteUltra)
        pintarLenteActiva(bP, bT, bU, estado.lente)
        bP.setOnClickListener { aplicar(estado.copy(lente = LenteFisica.PRINCIPAL)); pintarLenteActiva(bP, bT, bU, LenteFisica.PRINCIPAL) }
        bT.setOnClickListener { aplicar(estado.copy(lente = LenteFisica.TELEOBJETIVO)); pintarLenteActiva(bP, bT, bU, LenteFisica.TELEOBJETIVO) }
        bU.setOnClickListener { aplicar(estado.copy(lente = LenteFisica.ULTRA_GRAN_ANGULAR)); pintarLenteActiva(bP, bT, bU, LenteFisica.ULTRA_GRAN_ANGULAR) }

        vista.findViewById<Button>(R.id.btnPresetMacro).setOnClickListener {
            aplicar(controller.estado.copy(
                lente = LenteFisica.PRINCIPAL,
                focoManual = true,
                distanciaFocoDioptras = 9.5f,
                temporizador = Temporizador.S2
            ))
            onCerrar()
        }

        val tOff = vista.findViewById<Button>(R.id.btnTimerOff)
        val t2 = vista.findViewById<Button>(R.id.btnTimer2)
        val t5 = vista.findViewById<Button>(R.id.btnTimer5)
        val t10 = vista.findViewById<Button>(R.id.btnTimer10)
        pintarTimerActivo(tOff, t2, t5, t10, estado.temporizador)
        tOff.setOnClickListener { aplicar(estado.copy(temporizador = Temporizador.OFF)); pintarTimerActivo(tOff, t2, t5, t10, Temporizador.OFF) }
        t2.setOnClickListener { aplicar(estado.copy(temporizador = Temporizador.S2)); pintarTimerActivo(tOff, t2, t5, t10, Temporizador.S2) }
        t5.setOnClickListener { aplicar(estado.copy(temporizador = Temporizador.S5)); pintarTimerActivo(tOff, t2, t5, t10, Temporizador.S5) }
        t10.setOnClickListener { aplicar(estado.copy(temporizador = Temporizador.S10)); pintarTimerActivo(tOff, t2, t5, t10, Temporizador.S10) }

        vista.findViewById<Button>(R.id.btnCerrarAjustes).setOnClickListener { onCerrar() }

        vista.findViewById<Switch>(R.id.switchIso).apply {
            isChecked = estado.isoManual
            setOnCheckedChangeListener { _, checked -> aplicar(controller.estado.copy(isoManual = checked)) }
        }
        vista.findViewById<Switch>(R.id.switchExp).apply {
            isChecked = estado.exposicionManual
            setOnCheckedChangeListener { _, checked ->
                val base = if (checked) controller.estado.copy(largaExposicion = false) else controller.estado
                aplicar(base.copy(exposicionManual = checked))
            }
        }
        vista.findViewById<Switch>(R.id.switchLarga).apply {
            isChecked = estado.largaExposicion
            setOnCheckedChangeListener { _, checked ->
                val base = if (checked) controller.estado.copy(exposicionManual = false) else controller.estado
                aplicar(base.copy(largaExposicion = checked))
            }
        }
        vista.findViewById<Switch>(R.id.switchFoco).apply {
            isChecked = estado.focoManual
            setOnCheckedChangeListener { _, checked -> aplicar(controller.estado.copy(focoManual = checked)) }
        }
        vista.findViewById<Switch>(R.id.switchWb).apply {
            isChecked = estado.wbManual
            setOnCheckedChangeListener { _, checked -> aplicar(controller.estado.copy(wbManual = checked)) }
        }

        vista.findViewById<Button>(R.id.btnResetPro).setOnClickListener {
            aplicar(CamaraEstado())
            configurar()
        }
    }

    private fun mostrarMenuDiagnostico() {
        val opciones = arrayOf("📊 Diagnóstico completo", "🧪 Test de apertura de IDs ocultos")
        AlertDialog.Builder(vista.context)
            .setTitle("🔍 Herramientas de diagnóstico")
            .setItems(opciones) { _, which ->
                when (which) {
                    0 -> mostrarDiagnostico()
                    1 -> mostrarTestApertura()
                }
            }
            .show()
    }

    private fun mostrarDiagnostico() {
        val texto = controller.obtenerDiagnostico()
        AlertDialog.Builder(vista.context)
            .setTitle("🔍 Diagnóstico")
            .setMessage(texto)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("📋 Copiar") { _, _ -> copiar(texto) }
            .show()
    }

    private fun mostrarTestApertura() {
        val texto = controller.probarAperturaDeIds()
        AlertDialog.Builder(vista.context)
            .setTitle("🧪 Test de apertura")
            .setMessage(texto)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("📋 Copiar") { _, _ -> copiar(texto) }
            .show()
    }

    private fun mostrarLog() {
        val texto = controller.obtenerLog()
        AlertDialog.Builder(vista.context)
            .setTitle("🐞 Log de actividad")
            .setMessage(texto)
            .setPositiveButton("Cerrar", null)
            .setNegativeButton("🗑 Limpiar") { _, _ -> controller.limpiarLog() }
            .setNeutralButton("📋 Copiar") { _, _ -> copiar(texto) }
            .show()
    }

    private fun copiar(texto: String) {
        val cb = vista.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("data", texto))
    }

    private fun pintarLenteActiva(bP: Button, bT: Button, bU: Button, lente: LenteFisica) {
        val on = 0xFF6C5CE7.toInt(); val off = 0xFF3D3D62.toInt()
        bP.setBackgroundColor(if (lente == LenteFisica.PRINCIPAL) on else off)
        bT.setBackgroundColor(if (lente == LenteFisica.TELEOBJETIVO) on else off)
        bU.setBackgroundColor(if (lente == LenteFisica.ULTRA_GRAN_ANGULAR) on else off)
    }

    private fun pintarTimerActivo(tOff: Button, t2: Button, t5: Button, t10: Button, t: Temporizador) {
        val on = 0xFF6C5CE7.toInt(); val off = 0xFF3D3D62.toInt()
        tOff.setBackgroundColor(if (t == Temporizador.OFF) on else off)
        t2.setBackgroundColor(if (t == Temporizador.S2) on else off)
        t5.setBackgroundColor(if (t == Temporizador.S5) on else off)
        t10.setBackgroundColor(if (t == Temporizador.S10) on else off)
    }

    private fun aplicar(nuevo: CamaraEstado) {
        controller.aplicarEstado(nuevo, cicloDeVida, vistaPrevia)
        onCambio()
    }
}