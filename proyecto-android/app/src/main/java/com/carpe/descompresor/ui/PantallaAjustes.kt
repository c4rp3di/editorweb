package com.carpe.descompresor.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.carpe.descompresor.apk.GestorKeystore
import com.carpe.descompresor.util.CopiaLocal
import com.carpe.descompresor.util.DetectorDispositivo
import com.carpe.descompresor.util.Formato
import com.carpe.descompresor.util.PreferenciasApp
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class PantallaAjustes(
    private val activity: AppCompatActivity,
    private val vm: ContenedorViewModel,
    private val lanz: Lanzadores
) {
    private val prefs = PreferenciasApp(activity)
    private val scroll = ScrollView(activity)
    private val contenido = Ui.vertical(activity, 16)
    val vista: View get() = scroll

    init {
        scroll.addView(contenido)
        construir()
    }

    // ---------- utilidades de filas ----------
    private fun seccion(titulo: String) {
        contenido.addView(Ui.texto(activity, titulo, 17f, true), Ui.anchoTotal(activity, 20))
        contenido.addView(Ui.separador(activity), Ui.anchoTotal(activity, 4))
    }

    private fun nota(texto: String) {
        val t = Ui.texto(activity, texto, 11f)
        t.alpha = 0.7f
        contenido.addView(t)
    }

    private fun interruptor(titulo: String, clave: String, alCambiar: ((Boolean) -> Unit)? = null) {
        val s = SwitchMaterial(activity)
        s.text = titulo
        s.textSize = 14f * Ui.escala
        s.isChecked = prefs.bool(clave)
        s.setOnCheckedChangeListener { _: CompoundButton, marcado: Boolean ->
            prefs.poner(clave, marcado)
            alCambiar?.invoke(marcado)
        }
        contenido.addView(s, Ui.anchoTotal(activity, 4))
    }

    private fun numero(titulo: String, clave: String, min: Int, max: Int, unidad: String) {
        val fila = Ui.fila(activity)
        val t = Ui.texto(activity, "$titulo ($min–$max $unidad)", 14f)
        t.layoutParams = Ui.pesoUno()
        val campo = EditText(activity)
        campo.inputType = InputType.TYPE_CLASS_NUMBER
        campo.setSingleLine(true)
        campo.setText(prefs.entero(clave).toString())
        campo.minimumWidth = Ui.dp(activity, 90)
        campo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull()
                if (v != null && v in min..max) prefs.poner(clave, v)
            }
        })
        campo.setOnFocusChangeListener { _, tiene ->
            if (!tiene) campo.setText(prefs.entero(clave).toString())
        }
        fila.addView(t)
        fila.addView(campo)
        contenido.addView(fila, Ui.anchoTotal(activity, 4))
    }

    private fun opciones(titulo: String, clave: String, valores: List<Pair<String, String>>, alCambiar: ((String) -> Unit)? = null) {
        contenido.addView(Ui.texto(activity, titulo, 14f), Ui.anchoTotal(activity, 6))
        val sp = Spinner(activity)
        sp.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, valores.map { it.first })
        val actual = prefs.cadena(clave)
        sp.setSelection(valores.indexOfFirst { it.second == actual }.coerceAtLeast(0))
        var primera = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (primera) {
                    primera = false
                    return
                }
                val nuevo = valores[pos].second
                prefs.poner(clave, nuevo)
                alCambiar?.invoke(nuevo)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        contenido.addView(sp)
    }

    private fun pedirTexto(titulo: String, oculto: Boolean, inicial: String, cb: (String) -> Unit) {
        val campo = EditText(activity)
        campo.setText(inicial)
        campo.setSingleLine(true)
        if (oculto) campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val cont = Ui.vertical(activity, 16)
        cont.addView(campo)
        AlertDialog.Builder(activity)
            .setTitle(titulo)
            .setView(cont)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aceptar") { _, _ -> cb(campo.text.toString()) }
            .show()
    }

    // ---------- pantalla ----------
    private fun construir() {
        contenido.removeAllViews()

        seccion("General")
        opciones("Tema", "tema", listOf("Sistema" to "sistema", "Claro" to "claro", "Oscuro" to "oscuro")) { v ->
            AppCompatDelegate.setDefaultNightMode(
                when (v) {
                    "claro" -> AppCompatDelegate.MODE_NIGHT_NO
                    "oscuro" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
        contenido.addView(Ui.texto(activity, "Tamaño de fuente de la app", 14f), Ui.anchoTotal(activity, 6))
        val spFuente = Spinner(activity)
        val escalas = listOf(85, 100, 115, 130)
        spFuente.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, escalas.map { "$it %" })
        spFuente.setSelection(escalas.indexOf(prefs.entero("escala_fuente")).coerceAtLeast(1))
        var primeraFuente = true
        spFuente.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (primeraFuente) {
                    primeraFuente = false
                    return
                }
                prefs.poner("escala_fuente", escalas[pos])
                Ui.escala = escalas[pos] / 100f
                activity.recreate()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        contenido.addView(spFuente)
        nota("Idioma: español (la interfaz está preparada para traducirse).")

        seccion("Extracción")
        opciones("Destino por defecto", "destino_defecto", listOf("Carpeta elegida (SAF)" to "saf", "Almacenamiento (acceso total)" to "almacenamiento", "Interno de la app" to "interno"))
        interruptor("Conservar estructura de carpetas", "preservar")
        opciones("Si el archivo ya existe", "si_existe", listOf("Renombrar con (1)" to "renombrar", "Sobrescribir" to "sobrescribir", "Omitir" to "omitir"))
        interruptor("Verificar CRC al extraer", "verificar_crc")
        numero("Buffer de copia", "buffer_kb", 16, 1024, "KB")
        nota("La extracción es secuencial (un archivo cada vez) en todos los formatos.")

        seccion("Seguridad y límites")
        numero("Tamaño máx. del contenedor", "max_contenedor_mb", 50, 4096, "MB")
        numero("Tamaño máx. de archivo recibido de otras apps", "max_recibido_mb", 50, 4096, "MB")
        numero("Tamaño máx. por entrada", "max_entrada_mb", 1, 2048, "MB")
        numero("Tamaño total máx. extraído", "max_total_mb", 1, 65536, "MB")
        numero("Ratio máx. descomprimido:comprimido", "ratio_max", 10, 1000, ":1")
        numero("Nº máximo de entradas", "max_entradas", 100, 100000, "")
        nota("Las rutas con «..», absolutas o con letra de unidad se rechazan siempre. No es configurable.")

        seccion("Vista previa")
        interruptor("Vista previa habilitada", "prev_habilitada")
        numero("Texto máx. a previsualizar", "prev_texto_kb", 10, 10240, "KB")
        numero("Imagen máx. a previsualizar", "prev_imagen_mp", 1, 64, "MP")
        interruptor("Permitir scripts en vista previa HTML", "prev_html_scripts")
        opciones("Codificación por defecto si falla la detección", "codificacion_defecto", listOf("UTF-8" to "UTF-8", "Latin-1" to "Latin-1"))

        seccion("APK / Firma")
        interruptor("Firma v1 (jar)", "firma_v1")
        interruptor("Firma v2", "firma_v2")
        interruptor("Firma v3", "firma_v3")
        interruptor("Verificar firma tras firmar", "verificar_tras_firmar")
        interruptor("Verificar alineación tras alinear", "verificar_alineacion")
        nota("v1 solo hace falta si el APK soporta Android 6 o anterior.")
        construirKeystores()

        seccion("Rendimiento / dispositivo")
        contenido.addView(Ui.monoespaciado(activity, DetectorDispositivo.resumen(activity), 11f))
        contenido.addView(Ui.botonSecundario(activity, "Restaurar ajustes recomendados para este dispositivo") {
            AlertDialog.Builder(activity)
                .setTitle("Restaurar")
                .setMessage("Se restablecerán todos los ajustes a los valores recomendados para este dispositivo.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar") { _, _ ->
                    prefs.restablecerTodo(activity)
                    Ui.escala = prefs.entero("escala_fuente") / 100f
                    construir()
                    Ui.toast(activity, "Ajustes restaurados")
                }
                .show()
        }, Ui.anchoTotal(activity, 6))

        seccion("Avanzado")
        interruptor("Modo desarrollador (logs detallados)", "modo_dev")
        contenido.addView(Ui.botonSecundario(activity, "Exportar ajustes (JSON)") { exportar() }, Ui.anchoTotal(activity, 6))
        contenido.addView(Ui.botonSecundario(activity, "Importar ajustes (JSON)") { importar() })
        contenido.addView(Ui.botonSecundario(activity, "Limpiar caché y extracciones internas") { limpiar() })
        contenido.addView(
            Ui.monoespaciado(
                activity,
                "Contenedores abiertos: ${prefs.largo("stat_abiertos")}\nArchivos extraídos: ${prefs.largo("stat_extraidas")}\nBytes procesados: ${Formato.bytes(prefs.largo("stat_bytes"))}",
                11f
            ),
            Ui.anchoTotal(activity, 8)
        )
        nota("Descompresor 1.0.0 · com.carpe.descompresor")
    }

    // ---------- keystores ----------
    private fun construirKeystores() {
        contenido.addView(Ui.texto(activity, "Keystores", 14f, true), Ui.anchoTotal(activity, 10))
        try {
            val lista = GestorKeystore.listar(activity)
            val activa = GestorKeystore.activa(activity)
            if (lista.isEmpty()) {
                contenido.addView(Ui.texto(activity, "No hay ninguna keystore importada.", 12f))
            }
            for (k in lista) {
                val fila = Ui.fila(activity)
                val t = Ui.texto(activity, (if (k.id == activa) "● " else "○ ") + k.nombre + " (" + k.alias + ")", 13f)
                t.layoutParams = Ui.pesoUno()
                fila.addView(t)
                fila.addView(Ui.botonSecundario(activity, "Usar") {
                    GestorKeystore.seleccionar(activity, k.id)
                    construir()
                })
                fila.addView(Ui.botonSecundario(activity, "Borrar") {
                    AlertDialog.Builder(activity)
                        .setTitle("Borrar keystore")
                        .setMessage("La keystore es tu llave: si la pierdes no podrás firmar actualizaciones de apps ya instaladas. ¿Borrar «${k.nombre}» de esta app?")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Borrar") { _, _ ->
                            GestorKeystore.eliminar(activity, k.id)
                            construir()
                        }
                        .show()
                })
                contenido.addView(fila)
            }
        } catch (t: Throwable) {
            contenido.addView(Ui.texto(activity, "No se pudo acceder al almacén cifrado: " + Ui.mensajeError(t), 12f))
        }
        contenido.addView(Ui.boton(activity, "Importar keystore (.jks / .keystore / .p12)") { importarKeystore() }, Ui.anchoTotal(activity, 6))
        nota("Se aceptan JKS clásico, PKCS12 y BKS. Las contraseñas se guardan cifradas con Android Keystore.")
    }

    private fun importarKeystore() {
        lanz.abrirDocumento { uri ->
            if (uri == null) return@abrirDocumento
            activity.lifecycleScope.launch {
                try {
                    val nombre = CopiaLocal.nombreDe(activity, uri)
                    val tmp = withContext(Dispatchers.IO) { GestorKeystore.copiarTemporal(activity, uri) }
                    pedirTexto("Contraseña del keystore", true, "") { ps ->
                        activity.lifecycleScope.launch {
                            try {
                                val aliases = withContext(Dispatchers.IO) { GestorKeystore.abrirAlmacen(tmp, ps.toCharArray()).aliases() }
                                if (aliases.isEmpty()) throw IOException("El keystore no contiene claves privadas")
                                if (aliases.size == 1) {
                                    pedirClave(tmp, nombre, aliases[0], ps)
                                } else {
                                    AlertDialog.Builder(activity)
                                        .setTitle("Elige el alias")
                                        .setItems(aliases.toTypedArray()) { _, i -> pedirClave(tmp, nombre, aliases[i], ps) }
                                        .show()
                                }
                            } catch (t: Throwable) {
                                tmp.delete()
                                Ui.toast(activity, Ui.mensajeError(t))
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Ui.toast(activity, "No se pudo leer el keystore: " + Ui.mensajeError(t))
                }
            }
        }
    }

    private fun pedirClave(tmp: File, nombre: String, alias: String, passStore: String) {
        pedirTexto("Contraseña de la clave «$alias»", true, passStore) { pk ->
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { GestorKeystore.guardar(activity, tmp, nombre, alias, passStore, pk) }
                    Ui.toast(activity, "✔ Keystore importada y activada")
                    construir()
                } catch (t: Throwable) {
                    Ui.toast(activity, "No se pudo importar: " + Ui.mensajeError(t))
                }
            }
        }
    }

    // ---------- avanzado ----------
    private fun exportar() {
        lanz.crearDocumento("ajustes_descompresor.json") { uri ->
            if (uri == null) return@crearDocumento
            try {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(prefs.exportarJson().toByteArray(Charsets.UTF_8)) }
                Ui.toast(activity, "✔ Ajustes exportados")
            } catch (t: Throwable) {
                Ui.toast(activity, "No se pudo exportar: " + Ui.mensajeError(t))
            }
        }
    }

    private fun importar() {
        lanz.abrirDocumento { uri ->
            if (uri == null) return@abrirDocumento
            try {
                val texto = activity.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
                    ?: throw IOException("No se pudo leer el archivo")
                val n = prefs.importarJson(texto)
                Ui.escala = prefs.entero("escala_fuente") / 100f
                construir()
                Ui.toast(activity, "✔ $n ajustes importados")
            } catch (t: Throwable) {
                Ui.toast(activity, "No se pudo importar: " + Ui.mensajeError(t))
            }
        }
    }

    private fun limpiar() {
        activity.lifecycleScope.launch {
            val liberado = withContext(Dispatchers.IO) {
                var total = 0L
                val carpetas = listOf(
                    File(activity.cacheDir, "preview"),
                    File(activity.cacheDir, "compartir"),
                    File(activity.cacheDir, "salida_apk"),
                    File(activity.cacheDir, "ks_tmp"),
                    File(activity.cacheDir, "rar_tmp"),
                    File(activity.filesDir, "extracciones")
                )
                for (c in carpetas) {
                    if (c.exists()) {
                        total += c.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        c.deleteRecursively()
                    }
                }
                total
            }
            Ui.toast(activity, "Liberados " + Formato.bytes(liberado))
        }
    }
}
