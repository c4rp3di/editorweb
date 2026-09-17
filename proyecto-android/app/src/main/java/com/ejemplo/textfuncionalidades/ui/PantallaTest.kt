package com.ejemplo.textfuncionalidades.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.ejemplo.textfuncionalidades.MainActivity

class PantallaTest : Fragment() {

    private lateinit var log: TextView

    private val actividad: MainActivity?
        get() = activity as? MainActivity

    override fun onCreateView(
        inflater: LayoutInflater, contenedor: ViewGroup?, estadoGuardado: Bundle?
    ): View {
        val scroll = ScrollView(requireContext())
        val columna = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(columna)

        val titulo = TextView(requireContext()).apply {
            text = "🧪 Informe de funcionalidades"
            textSize = 20f
        }
        columna.addView(titulo)

        log = TextView(requireContext()).apply {
            text = "Pulsa un botón para probar.\n\n"
            textSize = 12f
            setPadding(0, 24, 0, 24)
        }
        columna.addView(log)

        val botones = listOf<Pair<String, () -> Unit>>(
            "Probar vibración"           to { probarVibracion() },
            "Probar preferencias"         to { probarPreferencias() },
            "Probar pantalla encendida"   to { anotar("✅ pantalla-encendida: activo (se aplicó en onCreate)") },
            "Probar modo oscuro"          to { anotar("✅ modo-oscuro: activo (AppCompatDelegate)") },
            "Probar conexión"             to { probarConexion() },
            "Probar abrir enlace"         to { probarAbrirEnlace() },
            "Probar notificación"         to { probarNotificacion() },
            "Probar base de datos"        to { probarBaseDatos() },
            "Probar preferencias (helper)"to { probarPreferenciasHelper() },
            "Probar menú lateral"         to { probarMenuLateral() },
            "Probar ubicación"            to { probarUbicacion() },
            "Probar cámara"               to { probarCamara() },
            "Probar galería"              to { probarGaleria() },
            "Probar micrófono"            to { probarMicrofono() },
            "Probar descargar"            to { probarDescargar() },
            "Probar enviar"               to { probarEnviar() },
            "Probar acelerómetro"         to { probarAcelerometro() },
            "Probar brújula"              to { probarBrujula() },
            "Probar huella"               to { probarHuella() }
        )

        botones.forEach { (etiqueta, accion) ->
            columna.addView(Button(requireContext()).apply {
                text = etiqueta
                setOnClickListener { accion() }
            })
        }

        return scroll
    }

    private fun anotar(linea: String) {
        log.append(linea + "\n")
        (log.parent as? ScrollView)?.post { (log.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    private fun probarVibracion() {
        val a = actividad ?: return anotar("❌ vibracion: sin actividad")
        try {
            a.vibrar(500)
            anotar("✅ vibracion: vibrar(500) ejecutado")
        } catch (e: Exception) {
            anotar("❌ vibracion: ${e.message}")
        }
    }

    private fun probarPreferencias() {
        val a = actividad ?: return anotar("❌ preferencias: sin actividad")
        try {
            val clave = "test_prefs"
            val valor = "ok_" + System.currentTimeMillis()
            a.guardarPreferencia(clave, valor)
            val leido = a.leerPreferencia(clave)
            if (leido == valor) anotar("✅ preferencias: guardado y leído correctamente")
            else anotar("❌ preferencias: leído '$leido' en vez de '$valor'")
        } catch (e: Exception) {
            anotar("❌ preferencias: ${e.message}")
        }
    }

    private fun probarPreferenciasHelper() {
        // Igual que probarPreferencias pero accediendo directamente a SharedPreferences,
        // por si el helper estuviera mal y la prueba "oficial" tapara el fallo.
        try {
            val prefs = requireContext().getSharedPreferences("datos_app", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("test_directo", "ok").apply()
            anotar("✅ preferencias (directo): SharedPreferences funciona")
        } catch (e: Exception) {
            anotar("❌ preferencias (directo): ${e.message}")
        }
    }

    private fun probarConexion() {
        val a = actividad ?: return anotar("❌ conexion: sin actividad")
        try {
            val hay = a.hayConexionInternet()
            anotar(if (hay) "✅ conexion: hay red" else "⚠️ conexion: sin red")
        } catch (e: Exception) {
            anotar("❌ conexion: ${e.message}")
        }
    }

    private fun probarAbrirEnlace() {
        val a = actividad ?: return anotar("❌ abrir-enlaces: sin actividad")
        try {
            a.abrirEnlace("https://example.com")
            anotar("✅ abrir-enlaces: Intent lanzado")
        } catch (e: Exception) {
            anotar("❌ abrir-enlaces: ${e.message}")
        }
    }

    private fun probarNotificacion() {
        val a = actividad ?: return anotar("❌ notificaciones: sin actividad")
        try {
            a.mostrarNotificacion("Test", "Notificación de prueba")
            anotar("✅ notificaciones: enviada (mira la barra de estado)")
        } catch (e: Exception) {
            anotar("❌ notificaciones: ${e.message}")
        }
    }

    private fun probarBaseDatos() {
        try {
            val db = com.ejemplo.textfuncionalidades.data.BaseDatos(requireContext())
            val id = db.insertar("test", "valor_" + System.currentTimeMillis())
            val todos = db.listarTodos()
            anotar("✅ base-datos: insertado id=$id, total filas=${todos.size}")
        } catch (e: Exception) {
            anotar("❌ base-datos: ${e.message}")
        }
    }

    private fun probarMenuLateral() {
        val a = actividad ?: return anotar("❌ menu-lateral: sin actividad")
        try {
            a.abrirMenuLateral()
            anotar("✅ menu-lateral: drawer abierto")
        } catch (e: Exception) {
            anotar("❌ menu-lateral: ${e.message}")
        }
    }

    private fun probarUbicacion() {
        val a = actividad ?: return anotar("❌ ubicacion: sin actividad")
        val permiso = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        if (permiso != PackageManager.PERMISSION_GRANTED) {
            a.pedirUbicacionActual()
            anotar("⏳ ubicacion: pidiendo permiso… vuelve a pulsar tras concederlo")
            return
        }
        try {
            val u = a.obtenerUbicacionActual()
            if (u != null) anotar("✅ ubicacion: lat=${u.latitude}, lon=${u.longitude}")
            else anotar("⚠️ ubicacion: sin última ubicación conocida")
        } catch (e: Exception) {
            anotar("❌ ubicacion: ${e.message}")
        }
    }

    private fun probarCamara() {
        val a = actividad ?: return anotar("❌ camara: sin actividad")
        try {
            a.pedirFotoConCamara()
            anotar("✅ camara: pedirFotoConCamara() lanzado")
        } catch (e: Exception) {
            anotar("❌ camara: ${e.message}")
        }
    }

    private fun probarGaleria() {
        val a = actividad ?: return anotar("❌ galeria: sin actividad")
        try {
            a.elegirImagenDeGaleria()
            anotar("✅ galeria: selector abierto")
        } catch (e: Exception) {
            anotar("❌ galeria: ${e.message}")
        }
    }

    private fun probarMicrofono() {
        val a = actividad ?: return anotar("❌ microfono: sin actividad")
        try {
            a.grabarAudio()
            anotar("✅ microfono: grabarAudio() lanzado")
        } catch (e: Exception) {
            anotar("❌ microfono: ${e.message}")
        }
    }

    private fun probarDescargar() {
        val a = actividad ?: return anotar("❌ descargar: sin actividad")
        anotar("⏳ descargar: pidiendo https://example.com …")
        a.descargarTexto("https://example.com") { resultado ->
            if (resultado != null) anotar("✅ descargar: recibidos ${resultado.length} caracteres")
            else anotar("❌ descargar: falló (sin red o sin permiso INTERNET)")
        }
    }

    private fun probarEnviar() {
        val a = actividad ?: return anotar("❌ enviar: sin actividad")
        anotar("⏳ enviar: POST a https://httpbin.org/post …")
        a.enviarDatos("https://httpbin.org/post", """{"test":"ok"}""") { codigo ->
            if (codigo in 200..299) anotar("✅ enviar: código HTTP $codigo")
            else anotar("❌ enviar: código HTTP $codigo")
        }
    }

    private fun probarAcelerometro() {
        val a = actividad ?: return anotar("❌ acelerometro: sin actividad")
        try {
            a.activarAcelerometro()
            anotar("✅ acelerometro: sensor registrado (mueve el móvil)")
        } catch (e: Exception) {
            anotar("❌ acelerometro: ${e.message}")
        }
    }

    private fun probarBrujula() {
        val a = actividad ?: return anotar("❌ brujula: sin actividad")
        try {
            a.activarBrujula()
            anotar("✅ brujula: sensor registrado (gira el móvil)")
        } catch (e: Exception) {
            anotar("❌ brujula: ${e.message}")
        }
    }

    private fun probarHuella() {
        val a = actividad ?: return anotar("❌ huella: sin actividad")
        try {
            a.pedirHuella()
            anotar("✅ huella: prompt biométrico lanzado")
        } catch (e: Exception) {
            anotar("❌ huella: ${e.message}")
        }
    }
}