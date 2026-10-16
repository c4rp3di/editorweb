package com.vigia.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.vigia.app.alert.AjustesAlertas
import com.vigia.app.alert.Alertas
import com.vigia.app.analyze.AjustesAnalisis
import com.vigia.app.analyze.Reglas
import com.vigia.app.analyze.base.MotorBase
import com.vigia.app.analyze.sig.IocManager
import com.vigia.app.analyze.sig.ResultadoIoc
import com.vigia.app.store.AppDatabase
import com.vigia.app.util.Formato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ajustes del análisis: indicadores de compromiso (descarga solo cuando el usuario lo pide o lo activa),
 * reglas H1-H10 visibles y editables, parámetros y estado de la línea base.
 */
class AjustesActivity : AppCompatActivity() {

    private lateinit var ajustes: AjustesAnalisis
    private lateinit var txtIoc: TextView
    private lateinit var txtFuentes: TextView
    private lateinit var txtBase: TextView
    private lateinit var etFuente: EditText
    private lateinit var btnActualizar: MaterialButton

    private lateinit var etH1Inicio: EditText
    private lateinit var etH1Fin: EditText
    private lateinit var etH8Min: EditText
    private lateinit var etCorrVentana: EditText
    private lateinit var etBaseMin: EditText

    private lateinit var alertas: AjustesAlertas
    private lateinit var chkSms: MaterialCheckBox
    private val valoresUmbral = arrayOf("critica", "alta", "media")
    private val valoresRetencion = arrayOf("conservar", "cerrar", "siete_dias")

    private val lanzadorPermisoSms = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (!concedido) {
            chkSms.isChecked = false
            Toast.makeText(this, R.string.sms_sin_permiso, Toast.LENGTH_LONG).show()
        }
    }

    private val lanzadorArchivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            txtIoc.setText(R.string.importando)
            lifecycleScope.launch {
                val r = IocManager.importarArchivo(this@AjustesActivity, uri)
                mostrarResultado(r)
                refrescar()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ajustes)
        ajustes = AjustesAnalisis(applicationContext)

        txtIoc = findViewById(R.id.txtIoc)
        txtFuentes = findViewById(R.id.txtFuentes)
        txtBase = findViewById(R.id.txtBase)
        etFuente = findViewById(R.id.etFuente)
        btnActualizar = findViewById(R.id.btnActualizarIoc)
        etH1Inicio = findViewById(R.id.etH1Inicio)
        etH1Fin = findViewById(R.id.etH1Fin)
        etH8Min = findViewById(R.id.etH8Min)
        etCorrVentana = findViewById(R.id.etCorrVentana)
        etBaseMin = findViewById(R.id.etBaseMin)
        val chkIndice = findViewById<MaterialCheckBox>(R.id.chkIndiceMvt)
        val chkAuto = findViewById<MaterialCheckBox>(R.id.chkAutoIoc)

        findViewById<MaterialButton>(R.id.btnVolver).setOnClickListener { finish() }

        chkIndice.isChecked = ajustes.usarIndiceMvt
        chkIndice.setOnCheckedChangeListener { _, marcado -> ajustes.usarIndiceMvt = marcado }
        chkAuto.isChecked = ajustes.actualizarAlIniciar
        chkAuto.setOnCheckedChangeListener { _, marcado -> ajustes.actualizarAlIniciar = marcado }

        findViewById<MaterialButton>(R.id.btnAnadirFuente).setOnClickListener { anadirFuente() }
        btnActualizar.setOnClickListener { actualizarIndicadores() }
        findViewById<MaterialButton>(R.id.btnImportarIoc).setOnClickListener {
            lanzadorArchivo.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.btnBorrarIoc).setOnClickListener { confirmarBorrarIoc() }
        findViewById<MaterialButton>(R.id.btnGuardarParametros).setOnClickListener { guardarParametros() }
        findViewById<MaterialButton>(R.id.btnReiniciarBase).setOnClickListener { confirmarReiniciarBase() }

        configurarAlertas()
        construirReglas()
        cargarParametros()
        refrescar()
    }


    private fun configurarAlertas() {
        alertas = AjustesAlertas(applicationContext)
        val chkLocal = findViewById<MaterialCheckBox>(R.id.chkNotifLocal)
        val chkHttps = findViewById<MaterialCheckBox>(R.id.chkHttps)
        chkSms = findViewById(R.id.chkSms)
        val etUrl = findViewById<EditText>(R.id.etUrlHttps)
        val etToken = findViewById<EditText>(R.id.etTokenHttps)
        val etSms = findViewById<EditText>(R.id.etSmsNumero)
        val spUmbral = findViewById<Spinner>(R.id.spUmbral)
        val spRetencion = findViewById<Spinner>(R.id.spRetencion)

        chkLocal.isChecked = alertas.notificacionLocal
        chkLocal.setOnCheckedChangeListener { _, marcado -> alertas.notificacionLocal = marcado }
        chkHttps.isChecked = alertas.httpsActivo
        chkHttps.setOnCheckedChangeListener { _, marcado -> alertas.httpsActivo = marcado }
        chkSms.isChecked = alertas.smsActivo
        chkSms.setOnCheckedChangeListener { _, marcado ->
            alertas.smsActivo = marcado
            if (marcado && ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                lanzadorPermisoSms.launch(Manifest.permission.SEND_SMS)
            }
        }
        etUrl.setText(alertas.httpsUrl)
        etSms.setText(alertas.smsNumero)
        if (alertas.token().isNotEmpty()) etToken.hint = getString(R.string.token_guardado)

        val adaptadorUmbral = ArrayAdapter.createFromResource(this, R.array.umbral_opciones, android.R.layout.simple_spinner_item)
        adaptadorUmbral.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spUmbral.adapter = adaptadorUmbral
        spUmbral.setSelection(maxOf(0, valoresUmbral.indexOf(alertas.umbral)))
        spUmbral.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(padre: AdapterView<*>?, vista: View?, posicion: Int, id: Long) {
                alertas.umbral = valoresUmbral[posicion]
            }

            override fun onNothingSelected(padre: AdapterView<*>?) {
            }
        }

        val adaptadorRetencion = ArrayAdapter.createFromResource(this, R.array.retencion_opciones, android.R.layout.simple_spinner_item)
        adaptadorRetencion.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spRetencion.adapter = adaptadorRetencion
        spRetencion.setSelection(maxOf(0, valoresRetencion.indexOf(alertas.retencion)))
        spRetencion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(padre: AdapterView<*>?, vista: View?, posicion: Int, id: Long) {
                alertas.retencion = valoresRetencion[posicion]
            }

            override fun onNothingSelected(padre: AdapterView<*>?) {
            }
        }

        findViewById<MaterialButton>(R.id.btnGuardarAlertas).setOnClickListener {
            guardarAlertas()
            Toast.makeText(this, R.string.alertas_guardadas, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnPruebaHttps).setOnClickListener { probarCanal("https") }
        findViewById<MaterialButton>(R.id.btnPruebaSms).setOnClickListener { probarCanal("sms") }
    }

    private fun guardarAlertas() {
        val etUrl = findViewById<EditText>(R.id.etUrlHttps)
        val etToken = findViewById<EditText>(R.id.etTokenHttps)
        val etSms = findViewById<EditText>(R.id.etSmsNumero)
        alertas.httpsUrl = etUrl.text.toString().trim()
        alertas.smsNumero = etSms.text.toString().trim()
        val token = etToken.text.toString()
        if (token.isNotEmpty()) {
            alertas.fijarToken(token)
            etToken.setText("")
            etToken.hint = getString(R.string.token_guardado)
        }
    }

    private fun probarCanal(canal: String) {
        guardarAlertas()
        lifecycleScope.launch {
            val resultado = Alertas.prueba(applicationContext, canal)
            AlertDialog.Builder(this@AjustesActivity)
                .setTitle(if (canal == "https") R.string.prueba_https else R.string.prueba_sms)
                .setMessage(resultado)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density).toInt()

    private fun construirReglas() {
        val contenedor = findViewById<LinearLayout>(R.id.contenedorReglas)
        for (regla in Reglas.editables()) {
            val fila = LinearLayout(this)
            fila.orientation = LinearLayout.HORIZONTAL
            fila.gravity = Gravity.CENTER_VERTICAL
            fila.setPadding(0, dp(8), 0, dp(8))

            val textos = LinearLayout(this)
            textos.orientation = LinearLayout.VERTICAL
            val paramsTextos = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val titulo = TextView(this)
            titulo.text = regla.id + " · " + regla.titulo
            titulo.textSize = 14f
            titulo.setTypeface(titulo.typeface, android.graphics.Typeface.BOLD)
            val descripcion = TextView(this)
            descripcion.text = regla.descripcion
            descripcion.textSize = 12f
            textos.addView(titulo)
            textos.addView(descripcion)

            val interruptor = SwitchCompat(this)
            interruptor.isChecked = ajustes.activa(regla.id)
            interruptor.setOnCheckedChangeListener { _, marcado -> ajustes.activar(regla.id, marcado) }

            fila.addView(textos, paramsTextos)
            fila.addView(interruptor)
            contenedor.addView(fila)
        }
    }

    private fun cargarParametros() {
        etH1Inicio.setText(ajustes.entero(AjustesAnalisis.H1_INICIO, 2).toString())
        etH1Fin.setText(ajustes.entero(AjustesAnalisis.H1_FIN, 5).toString())
        etH8Min.setText(ajustes.entero(AjustesAnalisis.H8_MIN_CONSULTAS, 12).toString())
        etCorrVentana.setText(ajustes.entero(AjustesAnalisis.CORR_VENTANA_S, 60).toString())
        etBaseMin.setText(ajustes.entero(AjustesAnalisis.BASE_MIN_MUESTRAS, 5).toString())
    }

    private fun leer(campo: EditText, minimo: Int, maximo: Int, defecto: Int): Int {
        val valor = campo.text.toString().trim().toIntOrNull() ?: return defecto
        return if (valor < minimo) minimo else if (valor > maximo) maximo else valor
    }

    private fun guardarParametros() {
        ajustes.fijarEntero(AjustesAnalisis.H1_INICIO, leer(etH1Inicio, 0, 23, 2))
        ajustes.fijarEntero(AjustesAnalisis.H1_FIN, leer(etH1Fin, 0, 23, 5))
        ajustes.fijarEntero(AjustesAnalisis.H8_MIN_CONSULTAS, leer(etH8Min, 3, 1000, 12))
        ajustes.fijarEntero(AjustesAnalisis.CORR_VENTANA_S, leer(etCorrVentana, 5, 600, 60))
        ajustes.fijarEntero(AjustesAnalisis.BASE_MIN_MUESTRAS, leer(etBaseMin, 2, 30, 5))
        cargarParametros()
        android.widget.Toast.makeText(this, R.string.parametros_guardados, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun anadirFuente() {
        val url = etFuente.text.toString().trim()
        if (!url.startsWith("https://")) {
            android.widget.Toast.makeText(this, R.string.fuente_invalida, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val lista = ajustes.fuentesExtra().toMutableList()
        if (!lista.contains(url)) lista.add(url)
        ajustes.fijarFuentesExtra(lista)
        etFuente.setText("")
        refrescar()
    }

    private fun actualizarIndicadores() {
        btnActualizar.isEnabled = false
        txtIoc.setText(R.string.descargando)
        lifecycleScope.launch {
            val r = IocManager.actualizar(this@AjustesActivity)
            btnActualizar.isEnabled = true
            mostrarResultado(r)
            refrescar()
        }
    }

    private fun mostrarResultado(r: ResultadoIoc) {
        val sb = StringBuilder()
        sb.append("Fuentes leídas: ").append(r.fuentes).append("\nIndicadores: ").append(r.indicadores)
        if (r.errores.isNotEmpty()) {
            sb.append("\n\nProblemas:\n")
            for (e in r.errores.take(8)) sb.append("• ").append(e).append('\n')
            if (r.errores.size > 8) sb.append("… y ").append(r.errores.size - 8).append(" más")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ajustes_ioc)
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmarBorrarIoc() {
        AlertDialog.Builder(this)
            .setTitle(R.string.borrar_indicadores)
            .setMessage(R.string.confirmar_borrar_ioc)
            .setPositiveButton(R.string.borrar_indicadores) { _, _ ->
                lifecycleScope.launch {
                    IocManager.borrarTodo(this@AjustesActivity)
                    refrescar()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmarReiniciarBase() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reiniciar_base)
            .setMessage(R.string.confirmar_reiniciar_base)
            .setPositiveButton(R.string.reiniciar_base) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val dao = AppDatabase.obtener(applicationContext).analisisDao()
                        dao.borrarTodasMuestras()
                        dao.borrarBaseline()
                        dao.borrarDominiosVistos()
                    }
                    refrescar()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refrescar() {
        lifecycleScope.launch {
            val texto = withContext(Dispatchers.IO) { construirEstado() }
            txtIoc.text = texto.first
            txtBase.text = texto.second
            val extra = ajustes.fuentesExtra()
            txtFuentes.text = if (extra.isEmpty()) "" else "Fuentes añadidas:\n" + extra.joinToString("\n")
            txtFuentes.visibility = if (extra.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private suspend fun construirEstado(): Pair<String, String> {
        val dao = AppDatabase.obtener(applicationContext).analisisDao()
        val n = dao.contarIocs()
        val fuentes = dao.iocsPorFuente()
        val ultima = ajustes.ultimaActualizacionIoc
        val ioc = "Indicadores cargados: " + n + " en " + fuentes.size + " fuente(s). Última actualización: " +
            (if (ultima > 0L) Formato.fecha(ultima) else "nunca") + "."

        val muestras = dao.contarMuestras()
        val vistos = dao.contarDominiosVistos()
        val base = dao.todaBaseline()
        val minimo = ajustes.entero(AjustesAnalisis.BASE_MIN_MUESTRAS, 5)
        val sb = StringBuilder()
        sb.append("Muestras guardadas: ").append(muestras).append(" · dominios ya vistos: ").append(vistos).append('\n')
        if (base.isEmpty()) {
            sb.append("Todavía no hay línea base: se construye al cerrar cada sesión. Hacen falta ").append(minimo)
                .append(" muestras por franja horaria antes de dar avisos (modo aprendizaje).")
        } else {
            val porMetrica = base.groupBy { it.metrica }
            for ((metrica, filas) in porMetrica) {
                val maxMuestras = filas.maxOf { it.muestras }
                var suma = 0.0
                var p95 = 0.0
                for (f in filas) {
                    suma += f.media
                    if (f.p95 > p95) p95 = f.p95
                }
                sb.append("• ").append(MotorBase.nombreMetrica(metrica)).append(": ").append(filas.size)
                    .append(" franjas, hasta ").append(maxMuestras).append(" muestras, media ")
                    .append(String.format("%.1f", suma / filas.size)).append(", máx. p95 ")
                    .append(String.format("%.0f", p95)).append(if (maxMuestras < minimo) " (aprendiendo)" else "").append('\n')
            }
        }
        return Pair(ioc, sb.toString())
    }
}
