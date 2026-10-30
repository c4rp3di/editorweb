package com.vigia.adblock

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigia.core.inventario.AppInfo
import com.vigia.core.inventario.AppsDispositivo
import com.vigia.core.red.VpnEstado
import com.vigia.core.ui.ActividadBase
import com.vigia.core.ui.Dialogos
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import com.vigia.core.util.Formato
import com.vigia.core.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * El Laboratorio (heredero de NetLens): resumen por app, actividad reciente, revisión de permisos, exportación a CSV y
 * guía. Solo lee el registro en memoria de [Laboratorio]; no toca la red.
 */
class LaboratorioActivity : ActividadBase() {

    private lateinit var textoEstado: TextView
    private lateinit var botonRegistro: MaterialButton
    private lateinit var listaApps: LinearLayout
    private lateinit var listaActividad: LinearLayout

    private var firmaApps = ""
    private var firmaActividad = ""
    private val etiquetas = HashMap<String, String>()

    @Volatile
    private var inventario: Map<String, AppInfo> = emptyMap()

    private val guardarCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(Laboratorio.csv().toByteArray(Charsets.UTF_8)) }
            Dialogos.informar(this, "Exportado", "El CSV se ha guardado. Contiene nombres de apps y dominios: revísalo antes de compartirlo.")
        } catch (e: Exception) {
            Dialogos.informar(this, "No se pudo guardar", e.message ?: "Error desconocido")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        construirUi()
        // El inventario (permisos, instalador, score) tarda un poco: fuera del hilo principal.
        Thread {
            try {
                val lista = AppsDispositivo.cargar(applicationContext)
                inventario = lista.associateBy { it.paquete }
            } catch (e: Exception) {
            }
        }.start()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refrescar()
                    delay(2000L)
                }
            }
        }
    }

    // ------------------------------------------------------------------ interfaz

    private fun construirUi() {
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "Laboratorio"), Vistas.anchoCompleto(this, 2))
        raiz.addView(
            Vistas.texto(this, "Qué hace cada app en tu red. Todo en memoria, nada sale del móvil.", true),
            Vistas.anchoCompleto(this, 12)
        )

        val estado = Vistas.tarjeta(this)
        textoEstado = Vistas.texto(this, "")
        estado.addView(textoEstado, Vistas.anchoCompleto(this, 8))
        botonRegistro = Vistas.boton(this, "Activar registro") { alternarRegistro() }
        estado.addView(botonRegistro, Vistas.anchoCompleto(this, 0))
        raiz.addView(estado, Vistas.anchoCompleto(this, 12))

        val apps = Vistas.tarjeta(this)
        apps.addView(Vistas.subtitulo(this, "Apps"), Vistas.anchoCompleto(this, 4))
        apps.addView(Vistas.texto(this, "Ordenadas por tráfico. Toca una para ver el detalle.", true), Vistas.anchoCompleto(this, 8))
        listaApps = LinearLayout(this)
        listaApps.orientation = LinearLayout.VERTICAL
        apps.addView(listaApps, Vistas.anchoCompleto(this, 0))
        raiz.addView(apps, Vistas.anchoCompleto(this, 12))

        val act = Vistas.tarjeta(this)
        act.addView(Vistas.subtitulo(this, "Actividad reciente"), Vistas.anchoCompleto(this, 8))
        listaActividad = LinearLayout(this)
        listaActividad.orientation = LinearLayout.VERTICAL
        act.addView(listaActividad, Vistas.anchoCompleto(this, 0))
        raiz.addView(act, Vistas.anchoCompleto(this, 12))

        raiz.addView(Vistas.boton(this, "Revisar permisos de mis apps", TipoBoton.TONAL) { revisarPermisos() }, Vistas.anchoCompleto(this, 8))
        raiz.addView(Vistas.boton(this, "Exportar CSV", TipoBoton.TONAL) { exportar() }, Vistas.anchoCompleto(this, 8))
        raiz.addView(Vistas.boton(this, "Guía del laboratorio", TipoBoton.TONAL) { abrirGuia() }, Vistas.anchoCompleto(this, 8))
        raiz.addView(Vistas.boton(this, "Borrar registro", TipoBoton.PELIGRO) {
            Dialogos.confirmar(this, "Borrar registro", "Se vaciará el registro en memoria (resumen y actividad).", "Borrar") {
                Laboratorio.limpiar()
                firmaApps = ""
                firmaActividad = ""
                refrescar()
            }
        }, Vistas.anchoCompleto(this, 0))

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    private fun alternarRegistro() {
        Laboratorio.activar(this, !Laboratorio.activo)
        refrescar()
    }

    // ------------------------------------------------------------------ refresco

    private fun refrescar() {
        val registro = Laboratorio.activo
        val vpnActiva = VpnEstado.activa.value
        botonRegistro.text = if (registro) "Pausar registro" else "Activar registro"
        val modo = if (Ajustes.esProfundo(this)) "Profundo" else "Ligero"
        textoEstado.text = when {
            !registro -> "Registro apagado: el túnel no averigua qué app hace cada conexión (consumo mínimo)."
            !vpnActiva -> "Registro activo, pero la protección está apagada: no hay tráfico que observar. Enciéndela en la pantalla principal."
            else -> "Registrando en Modo " + modo + " · " + Laboratorio.totalLineas() + " eventos" +
                (if (Laboratorio.desde > 0L) " desde las " + Formato.hora(Laboratorio.desde) else "") +
                (if (modo == "Ligero") ".\nEn Modo Ligero solo se ven consultas DNS; el Modo Profundo añade destinos y bytes." else ".")
        }
        pintarApps()
        pintarActividad()
    }

    private fun pintarApps() {
        val filas = Laboratorio.resumen().take(30)
        val firma = filas.joinToString("|") { it.app + ":" + it.bytes + ":" + it.consultasDns }
        if (firma == firmaApps) return
        firmaApps = firma
        listaApps.removeAllViews()
        if (filas.isEmpty()) {
            listaApps.addView(Vistas.texto(this, "Todavía no hay datos.", true))
            return
        }
        for (f in filas) {
            var t = etiqueta(f.app) + "\n" + "↑ " + Formato.bytes(f.bytesOut) + "  ↓ " + Formato.bytes(f.bytesIn) +
                "  ·  " + f.destinos.size + " destinos  ·  " + f.consultasDns + " consultas DNS"
            if (f.bloqueadas > 0) t += "  ·  ⛔ " + f.bloqueadas
            if (f.httpClaro > 0) t += "  ·  ⚠ HTTP"
            val fila = Vistas.texto(this, t)
            fila.setPadding(0, Vistas.dp(this, 6), 0, Vistas.dp(this, 6))
            fila.setOnClickListener { detalle(f) }
            listaApps.addView(fila)
        }
    }

    private fun pintarActividad() {
        val lineas = Laboratorio.recientes(60)
        val firma = lineas.size.toString() + ":" + (if (lineas.isEmpty()) 0L else lineas[0].ts)
        if (firma == firmaActividad) return
        firmaActividad = firma
        listaActividad.removeAllViews()
        if (lineas.isEmpty()) {
            listaActividad.addView(Vistas.texto(this, "Sin actividad registrada.", true))
            return
        }
        for (l in lineas) {
            val t = Vistas.texto(
                this,
                Formato.hora(l.ts) + " " + (if (l.bloqueado) "⛔ " else "") + l.tipo + " · " + etiqueta(l.app) + " · " + l.texto,
                !l.bloqueado
            )
            t.typeface = Typeface.MONOSPACE
            t.textSize = 12f
            t.setPadding(0, Vistas.dp(this, 2), 0, Vistas.dp(this, 2))
            listaActividad.addView(t)
        }
    }

    private fun etiqueta(app: String): String {
        val guardada = etiquetas[app]
        if (guardada != null) return guardada
        val e = try {
            packageManager.getApplicationInfo(app, 0).loadLabel(packageManager).toString()
        } catch (ex: Exception) {
            app
        }
        etiquetas[app] = e
        return e
    }

    // ------------------------------------------------------------------ detalle por app

    private fun detalle(f: Laboratorio.FilaApp) {
        val info = inventario[f.app]
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(etiqueta(f.app))
            .setMessage(informe(f, info))
            .setPositiveButton("Cerrar", null)
        if (info != null) {
            b.setNeutralButton("Ajustes de la app") { _, _ -> abrirAjustesDe(f.app) }
        }
        b.show()
    }

    private fun informe(f: Laboratorio.FilaApp, info: AppInfo?): String {
        val sb = StringBuilder()
        sb.append("Aplicación: ").append(etiqueta(f.app)).append("  (").append(f.app).append(")\n\n")
        sb.append("Tráfico observado: ↑ ").append(Formato.bytes(f.bytesOut)).append("  ↓ ").append(Formato.bytes(f.bytesIn))
        sb.append("  ·  ").append(f.destinos.size).append(" destinos\n")
        sb.append("Consultas DNS: ").append(f.consultasDns).append(" (").append(f.bloqueadas).append(" respondidas sin salir a internet)\n")
        if (f.destinos.isNotEmpty()) {
            sb.append("\nDestinos principales:\n")
            for (d in f.destinos.take(12)) sb.append("  · ").append(d.first).append("  ").append(Formato.bytes(d.second)).append("\n")
            if (f.destinos.size > 12) sb.append("  …\n")
        }

        // Rastreadores y anuncios: lo que está en tus listas (no hace falta otra base de datos).
        val nombres = LinkedHashSet<String>()
        for (d in f.destinos) nombres.add(d.first)
        nombres.addAll(f.dominiosDns)
        val enListas = ArrayList<String>()
        if (AdBlockEstado.filtro.cargado) {
            for (n in nombres) {
                if (esIp(n)) continue
                if (AdBlockEstado.filtro.consultar(n) == FiltroAdBlock.BLOQUEADO) enListas.add(n)
            }
        }
        if (enListas.isNotEmpty()) {
            sb.append("\n🎯 En tus listas de bloqueo (").append(enListas.size).append("): ")
            sb.append(enListas.take(10).joinToString(", ")).append(if (enListas.size > 10) " …" else "").append("\n")
        }
        if (f.httpClaro > 0) {
            sb.append("\n⚠ ").append(f.httpClaro).append(" registros de conexiones al puerto 80 (HTTP sin cifrar): cualquiera en la red puede leerlas.\n")
        }

        if (info != null) {
            val concedidos = info.permisos.map { NOMBRES_PERMISO[it] ?: it }.distinct()
            sb.append("\nPermisos sensibles CONCEDIDOS: ")
            sb.append(if (concedidos.isEmpty()) "ninguno" else concedidos.joinToString(", ")).append("\n")
            sb.append("Revisión: ").append(info.nivel.uppercase()).append(" (").append(info.puntos).append(" puntos)")
            if (info.motivos.isNotEmpty()) sb.append(" — ").append(info.motivos.joinToString("; "))
            sb.append("\n")
            val ubicacion = info.permisos.contains("ACCESS_FINE_LOCATION") || info.permisos.contains("ACCESS_BACKGROUND_LOCATION")
            val delicados = info.permisos.any { it in DELICADOS }
            if (ubicacion && enListas.isNotEmpty()) {
                sb.append("\n📍 Alcance: puede leer tu ubicación y además contacta con dominios de tus listas de bloqueo. Es una pista, no una prueba de fuga.\n")
            } else if (delicados && enListas.isNotEmpty()) {
                sb.append("\n📍 Alcance: tiene permisos delicados y contacta con terceros de tus listas. Conviene revisar qué envía realmente.\n")
            }
        } else {
            sb.append("\n(Sin datos de permisos: solo se ven las apps con icono de lanzador.)\n")
        }
        sb.append("\nQué NO vemos sin descifrar: el contenido de las conexiones TLS.")
        return sb.toString()
    }

    private fun esIp(n: String): Boolean = n.isNotEmpty() && n.all { it.isDigit() || it == '.' || it == ':' }

    private fun abrirAjustesDe(paquete: String) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + paquete))
            startActivity(i)
        } catch (e: Exception) {
            Dialogos.informar(this, "No se pudo abrir", "Ve a Ajustes › Apps y busca la app.")
        }
    }

    // ------------------------------------------------------------------ permisos, exportación y guía

    private fun revisarPermisos() {
        val lista = inventario.values
            .filter { !it.sistema && it.nivel != "normal" }
            .sortedByDescending { it.puntos }
            .take(25)
        if (lista.isEmpty()) {
            Dialogos.informar(
                this, "Revisión de permisos",
                if (inventario.isEmpty()) "El inventario aún se está cargando o no se pudo leer. Inténtalo de nuevo en unos segundos."
                else "Ninguna de tus apps visibles destaca. Solo se ven las apps con icono de lanzador."
            )
            return
        }
        val opciones = lista.map { it.etiqueta + " · " + it.nivel.uppercase() + " — " + it.motivos.take(2).joinToString("; ") }
        Dialogos.elegir(this, "Apps a revisar (toca para abrir sus ajustes)", opciones) { i -> abrirAjustesDe(lista[i].paquete) }
    }

    private fun exportar() {
        if (Laboratorio.totalLineas() == 0) {
            Dialogos.informar(this, "Nada que exportar", "Activa el registro y deja que las apps trabajen un rato.")
            return
        }
        val nombre = "adblock-laboratorio-" + Formato.fechaHoraCompleta(System.currentTimeMillis()).replace(' ', '_').replace(':', '-') + ".csv"
        guardarCsv.launch(nombre)
    }

    private fun abrirGuia() {
        Dialogos.elegir(this, "Guía del laboratorio", Lecciones.TODAS.map { it.first }) { i ->
            Dialogos.informar(this, Lecciones.TODAS[i].first, Lecciones.TODAS[i].second, "Cerrar")
        }
    }

    companion object {
        private val NOMBRES_PERMISO = mapOf(
            "ACCESS_FINE_LOCATION" to "Ubicación precisa",
            "ACCESS_BACKGROUND_LOCATION" to "Ubicación en segundo plano",
            "CAMERA" to "Cámara",
            "RECORD_AUDIO" to "Micrófono",
            "READ_CONTACTS" to "Contactos",
            "READ_SMS" to "Leer SMS",
            "RECEIVE_SMS" to "Recibir SMS",
            "SEND_SMS" to "Enviar SMS",
            "READ_CALL_LOG" to "Registro de llamadas",
            "READ_PHONE_STATE" to "Estado del teléfono",
            "READ_CALENDAR" to "Calendario",
            "ANSWER_PHONE_CALLS" to "Contestar llamadas",
            "PROCESS_OUTGOING_CALLS" to "Llamadas salientes"
        )

        private val DELICADOS = setOf(
            "CAMERA", "RECORD_AUDIO", "READ_CONTACTS", "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CALL_LOG"
        )
    }
}
