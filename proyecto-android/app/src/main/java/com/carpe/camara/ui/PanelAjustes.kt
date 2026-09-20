package com.carpe.camara.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.carpe.camara.R
import com.carpe.camara.data.CamaraController
import com.carpe.camara.data.CamaraEstado
import com.carpe.camara.data.LenteFisica
import com.carpe.camara.data.ModoCaptura
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

        val txtAviso = vista.findViewById<TextView>(R.id.txtAvisoLente)
        txtAviso.text = if (controller.seleccionLenteRealDisponible) "✓ 3 lentes" else "zoom→lente"

        vista.findViewById<Button>(R.id.btnDiagnostico).setOnClickListener { mostrarDiagnostico() }

        // LENTE
        val bP = vista.findViewById<Button>(R.id.btnLentePrincipal)
        val bT = vista.findViewById<Button>(R.id.btnLenteTele)
        val bU = vista.findViewById<Button>(R.id.btnLenteUltra)
        pintarLenteActiva(bP, bT, bU, estado.lente)
        bP.setOnClickListener { aplicar(estado.copy(lente = LenteFisica.PRINCIPAL)); pintarLenteActiva(bP, bT, bU, LenteFisica.PRINCIPAL) }
        bT.setOnClickListener { aplicar(estado.copy(lente = LenteFisica.TELEOBJETIVO)); pintarLenteActiva(bP, bT, bU, LenteFisica.TELEOBJETIVO) }
        bU.setOnClickListener { aplicar(estado.copy(lente = LenteFisica.ULTRA_GRAN_ANGULAR)); pintarLenteActiva(bP, bT, bU, LenteFisica.ULTRA_GRAN_ANGULAR) }

        // PRESET MACRO: 1x + foco 9.5 dioptrías + timer 2s
        vista.findViewById<Button>(R.id.btnPresetMacro).setOnClickListener {
            val nuevo = controller.estado.copy(
                lente = LenteFisica.PRINCIPAL,
                focoManual = true,
                distanciaFocoDioptras = 9.5f,
                temporizador = Temporizador.S2
            )
            aplicar(nuevo)
            pintarLenteActiva(bP, bT, bU, LenteFisica.PRINCIPAL)
            onCerrar()
        }

        // TEMPORIZADOR
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

        // ISO 50–12800 (slider progress 0..12750, valor = progress + 50)
        val switchIso = vista.findViewById<Switch>(R.id.switchIso)
        val sliderIso = vista.findViewById<SeekBar>(R.id.sliderIso)
        val valorIso = vista.findViewById<TextView>(R.id.valorIso)
        switchIso.isChecked = estado.isoManual
        sliderIso.progress = (estado.iso - 50).coerceIn(0, 12750)
        sliderIso.isEnabled = estado.isoManual
        valorIso.text = estado.iso.toString()
        switchIso.setOnCheckedChangeListener { _, checked ->
            sliderIso.isEnabled = checked
            aplicar(controller.estado.copy(isoManual = checked))
        }
        sliderIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) valorIso.text = (p + 50).toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val iso = (sliderIso.progress + 50).coerceIn(50, 12800)
                aplicar(controller.estado.copy(iso = iso, isoManual = true))
            }
        })

        // EXPOSICIÓN
        val switchExp = vista.findViewById<Switch>(R.id.switchExp)
        val sliderExp = vista.findViewById<SeekBar>(R.id.sliderExp)
        val valorExp = vista.findViewById<TextView>(R.id.valorExp)
        switchExp.isChecked = estado.exposicionManual
        sliderExp.progress = progressDesdeNs(estado.exposicionNs)
        sliderExp.isEnabled = estado.exposicionManual
        valorExp.text = nsATexto(estado.exposicionNs)
        switchExp.setOnCheckedChangeListener { _, checked ->
            sliderExp.isEnabled = checked
            aplicar(controller.estado.copy(exposicionManual = checked))
        }
        sliderExp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) valorExp.text = nsATexto(nsDesdeProgress(p))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val ns = nsDesdeProgress(sliderExp.progress)
                aplicar(controller.estado.copy(exposicionNs = ns, exposicionManual = true))
            }
        })

        // EXPOSICIÓN LARGA - Presets
        vista.findViewById<Button>(R.id.btnLarga1s).setOnClickListener { aplicarLarga(1) }
        vista.findViewById<Button>(R.id.btnLarga2s).setOnClickListener { aplicarLarga(2) }
        vista.findViewById<Button>(R.id.btnLarga4s).setOnClickListener { aplicarLarga(4) }
        vista.findViewById<Button>(R.id.btnLarga8s).setOnClickListener { aplicarLarga(8) }
        vista.findViewById<Button>(R.id.btnLarga15s).setOnClickListener { aplicarLarga(15) }
        vista.findViewById<Button>(R.id.btnLarga30s).setOnClickListener { aplicarLarga(30) }
        vista.findViewById<Button>(R.id.btnLargaOff).setOnClickListener {
            aplicar(controller.estado.copy(modo = ModoCaptura.AUTO, exposicionManual = false))
            onCerrar()
        }

        // FOCO
        val switchFoco = vista.findViewById<Switch>(R.id.switchFoco)
        val sliderFoco = vista.findViewById<SeekBar>(R.id.sliderFoco)
        val valorFoco = vista.findViewById<TextView>(R.id.valorFoco)
        switchFoco.isChecked = estado.focoManual
        sliderFoco.progress = (estado.distanciaFocoDioptras / 10f * 100).toInt().coerceIn(0, 100)
        sliderFoco.isEnabled = estado.focoManual
        valorFoco.text = dioptrasATexto(estado.distanciaFocoDioptras)
        switchFoco.setOnCheckedChangeListener { _, checked ->
            sliderFoco.isEnabled = checked
            aplicar(controller.estado.copy(focoManual = checked))
        }
        sliderFoco.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) valorFoco.text = dioptrasATexto(p / 100f * 10f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val d = sliderFoco.progress / 100f * 10f
                aplicar(controller.estado.copy(distanciaFocoDioptras = d, focoManual = true))
            }
        })

        // FOCO FINO: tocar el valor abre un diálogo para escribir el número exacto
        valorFoco.setOnClickListener {
            val actual = controller.estado.distanciaFocoDioptras
            val edit = EditText(vista.context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("%.2f".format(actual))
                setSelection(text.length)
            }
            AlertDialog.Builder(vista.context)
                .setTitle("Enfoque fino (dioptrías)")
                .setMessage("0 = ∞ · 10 = ~10cm (macro)\nRango: 0.00 – 10.00")
                .setView(edit)
                .setPositiveButton("Aplicar") { _, _ ->
                    val txt = edit.text.toString().replace(',', '.')
                    val valor = txt.toFloatOrNull()
                    if (valor != null && valor in 0f..10f) {
                        sliderFoco.progress = (valor / 10f * 100).toInt().coerceIn(0, 100)
                        valorFoco.text = dioptrasATexto(valor)
                        aplicar(controller.estado.copy(distanciaFocoDioptras = valor, focoManual = true))
                        switchFoco.isChecked = true
                        sliderFoco.isEnabled = true
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        // WB
        val switchWb = vista.findViewById<Switch>(R.id.switchWb)
        val sliderWb = vista.findViewById<SeekBar>(R.id.sliderWb)
        val valorWb = vista.findViewById<TextView>(R.id.valorWb)
        switchWb.isChecked = estado.wbManual
        sliderWb.progress = (estado.temperaturaK - 2000).coerceAtLeast(0)
        sliderWb.isEnabled = estado.wbManual
        valorWb.text = "${estado.temperaturaK}K"
        switchWb.setOnCheckedChangeListener { _, checked ->
            sliderWb.isEnabled = checked
            aplicar(controller.estado.copy(wbManual = checked))
        }
        sliderWb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) valorWb.text = "${p + 2000}K"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val k = sliderWb.progress + 2000
                aplicar(controller.estado.copy(temperaturaK = k, wbManual = true))
            }
        })

        vista.findViewById<Button>(R.id.btnResetPro).setOnClickListener {
            aplicar(CamaraEstado())
            configurar()
        }
    }

    private fun aplicarLarga(segundos: Int) {
        val ns = segundos * 1_000_000_000L
        val base = controller.estado
        val nuevo = base.copy(
            modo = ModoCaptura.LARGA_EXPOSICION,
            exposicionLargaNs = ns
        )
        aplicar(nuevo)
    }

    private fun mostrarDiagnostico() {
        val texto = controller.obtenerDiagnostico()
        AlertDialog.Builder(vista.context)
            .setTitle("🔍 Diagnóstico de cámara")
            .setMessage(texto)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("📋 Copiar") { _, _ ->
                val cb = vista.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("diagnostico", texto))
            }
            .show()
    }

    private fun pintarLenteActiva(bP: Button, bT: Button, bU: Button, lente: LenteFisica) {
        val on = 0xFF6C5CE7.toInt()
        val off = 0xFF3D3D62.toInt()
        bP.setBackgroundColor(if (lente == LenteFisica.PRINCIPAL) on else off)
        bT.setBackgroundColor(if (lente == LenteFisica.TELEOBJETIVO) on else off)
        bU.setBackgroundColor(if (lente == LenteFisica.ULTRA_GRAN_ANGULAR) on else off)
    }

    private fun pintarTimerActivo(tOff: Button, t2: Button, t5: Button, t10: Button, t: Temporizador) {
        val on = 0xFF6C5CE7.toInt()
        val off = 0xFF3D3D62.toInt()
        tOff.setBackgroundColor(if (t == Temporizador.OFF) on else off)
        t2.setBackgroundColor(if (t == Temporizador.S2) on else off)
        t5.setBackgroundColor(if (t == Temporizador.S5) on else off)
        t10.setBackgroundColor(if (t == Temporizador.S10) on else off)
    }

    private fun aplicar(nuevo: CamaraEstado) {
        controller.aplicarEstado(nuevo, cicloDeVida, vistaPrevia)
        onCambio()
    }

    private fun nsATexto(ns: Long): String {
        val ms = ns / 1_000_000.0
        return if (ms < 1.0) "1/${(1000.0 / ms).toInt().coerceAtLeast(1)}"
        else "%.1fs".format(ms / 1000.0)
    }

    private fun progressDesdeNs(ns: Long): Int {
        val min = 125_000.0
        val max = 2_000_000_000.0
        val v = ns.toDouble().coerceIn(min, max)
        return ((Math.log(v / min) / Math.log(max / min)) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun nsDesdeProgress(p: Int): Long {
        val min = 125_000.0
        val max = 2_000_000_000.0
        return (min * Math.pow(max / min, p / 100.0)).toLong()
    }

    private fun dioptrasATexto(d: Float): String =
        if (d <= 0.05f) "∞" else "%.2f (%.0fcm)".format(d, 100f / d)
}