package com.vigia.nucleolab

import android.os.Bundle
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.core.inventario.AppsDispositivo
import com.vigia.core.red.UtilRed
import com.vigia.core.red.VpnController
import com.vigia.core.seguridad.CifradoArchivos
import com.vigia.core.ui.ActividadBase
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import com.vigia.core.util.Formato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Pantalla de verificación del núcleo: cada botón ejerce una pieza de :vigia-core y escribe el resultado.
 * Si todo sale «OK», el módulo compila y funciona en este teléfono.
 */
class MainActivity : ActividadBase() {

    private lateinit var vpn: VpnController
    private lateinit var botonVpn: MaterialButton
    private lateinit var estadoVpn: TextView
    private lateinit var campoDominio: EditText
    private lateinit var salida: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpn = VpnController.crear(this, LabVpnService::class.java)
        pedirPermisoNotificaciones()
        construirUi()

        lifecycleScope.launch {
            vpn.activa.collect { activa ->
                botonVpn.text = if (activa) "Detener VPN de prueba" else "Iniciar VPN de prueba"
                estadoVpn.text = refrescarEstado()
            }
        }
        lifecycleScope.launch {
            LabEstado.contador.total.collect { estadoVpn.text = refrescarEstado() }
        }
        lifecycleScope.launch {
            vpn.mensaje.collect { m -> if (m != null) escribir("VPN: " + m) }
        }
    }

    private fun refrescarEstado(): String {
        val estado = if (vpn.activa.value) "ACTIVA" else "apagada"
        return "VPN " + estado + " · dominios bloqueados hasta ahora: " + LabEstado.contador.total.value
    }

    private fun construirUi() {
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "Núcleo Lab"), Vistas.anchoCompleto(this, 4))
        raiz.addView(Vistas.texto(this, ":vigia-core · entrega 0 · prueba de cada pieza", true), Vistas.anchoCompleto(this, 16))

        // --- Túnel ---
        val t = Vistas.tarjeta(this)
        t.addView(Vistas.subtitulo(this, "1 · Túnel de red"), Vistas.anchoCompleto(this, 6))
        t.addView(
            Vistas.texto(this, "Bloquea el dominio de abajo (y sus subdominios) mientras la VPN esté activa. Con la VPN activa, «Resolver» debe dar 0.0.0.0.", true),
            Vistas.anchoCompleto(this, 8)
        )
        campoDominio = EditText(this)
        campoDominio.setText("example.com")
        campoDominio.setSingleLine(true)
        campoDominio.setTextColor(Vistas.color(this, com.vigia.core.R.color.vc_texto))
        t.addView(campoDominio, Vistas.anchoCompleto(this, 8))
        estadoVpn = Vistas.texto(this, "VPN apagada")
        t.addView(estadoVpn, Vistas.anchoCompleto(this, 8))
        botonVpn = Vistas.boton(this, "Iniciar VPN de prueba") { alternarVpn() }
        t.addView(botonVpn, Vistas.anchoCompleto(this, 8))
        t.addView(Vistas.boton(this, "Resolver el dominio", TipoBoton.TONAL) { resolver() }, Vistas.anchoCompleto(this, 0))
        raiz.addView(t, Vistas.anchoCompleto(this, 12))

        // --- Almacén seguro ---
        val a = Vistas.tarjeta(this)
        a.addView(Vistas.subtitulo(this, "2 · Almacén seguro"), Vistas.anchoCompleto(this, 6))
        a.addView(Vistas.texto(this, "Guarda una fila en la base SQLCipher y comprueba que el archivo no empieza por «SQLite format 3».", true), Vistas.anchoCompleto(this, 8))
        a.addView(Vistas.boton(this, "Probar base cifrada", TipoBoton.TONAL) { probarBd() }, Vistas.anchoCompleto(this, 8))
        a.addView(Vistas.texto(this, "Cifra y descifra un texto con contraseña (AES-256-GCM) y comprueba que una contraseña errónea falla.", true), Vistas.anchoCompleto(this, 8))
        a.addView(Vistas.boton(this, "Probar cifrado de archivos", TipoBoton.TONAL) { probarCifrado() }, Vistas.anchoCompleto(this, 0))
        raiz.addView(a, Vistas.anchoCompleto(this, 12))

        // --- Inventario ---
        val i = Vistas.tarjeta(this)
        i.addView(Vistas.subtitulo(this, "3 · Inventario y score"), Vistas.anchoCompleto(this, 6))
        i.addView(Vistas.boton(this, "Listar apps y puntuarlas", TipoBoton.TONAL) { probarInventario() }, Vistas.anchoCompleto(this, 0))
        raiz.addView(i, Vistas.anchoCompleto(this, 12))

        // --- Registro ---
        salida = Vistas.texto(this, "Resultados:")
        salida.setTextIsSelectable(true)
        raiz.addView(salida, Vistas.anchoCompleto(this, 8))

        val scroll = ScrollView(this)
        scroll.addView(raiz)
        setContentView(scroll)
    }

    private fun escribir(linea: String) {
        salida.text = salida.text.toString() + "\n" + linea
    }

    private fun alternarVpn() {
        if (vpn.activa.value) {
            vpn.stop()
            return
        }
        val dominio = UtilRed.normalizarDominio(campoDominio.text.toString())
        if (dominio == null) {
            escribir("Dominio no válido: «" + campoDominio.text + "»")
            return
        }
        asegurarVpn(vpn) {
            LabEstado.filtro.actualizar(listOf(dominio))
            LabEstado.contador.reiniciar()
            vpn.start()
            escribir("VPN iniciada. Bloqueando " + dominio)
        }
    }

    private fun resolver() {
        val dominio = campoDominio.text.toString().trim()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    InetAddress.getAllByName(dominio).joinToString(", ") { it.hostAddress ?: "?" }
                } catch (e: Exception) {
                    "falló (" + e.javaClass.simpleName + ")"
                }
            }
            escribir(dominio + " → " + r)
        }
    }

    private fun probarBd() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val bd = LabDb.obtener(applicationContext)
                    bd.notaDao().insertar(Nota(ts = System.currentTimeMillis(), texto = "nota de prueba"))
                    val filas = bd.notaDao().contar()
                    val archivo = applicationContext.getDatabasePath(LabDb.NOMBRE)
                    val cabecera = ByteArray(15)
                    archivo.inputStream().use { it.read(cabecera) }
                    val legible = String(cabecera, Charsets.ISO_8859_1) == "SQLite format 3"
                    if (legible) "BD: FALLO, el archivo es legible sin clave ($filas filas)"
                    else "BD: OK, $filas filas y el archivo no es legible sin la clave (" + Formato.bytes(archivo.length()) + ")"
                } catch (e: Exception) {
                    "BD: ERROR " + e.javaClass.simpleName + " " + (e.message ?: "")
                }
            }
            escribir(r)
        }
    }

    private fun probarCifrado() {
        lifecycleScope.launch {
            escribir("Cifrado: derivando claves (unos segundos)…")
            val r = withContext(Dispatchers.IO) {
                try {
                    val t0 = System.currentTimeMillis()
                    val cifrador = CifradoArchivos("LABTST")
                    val original = "texto confidencial de prueba".toByteArray(Charsets.UTF_8)
                    val paquete = cifrador.cifrar(original, "clave-de-prueba".toCharArray())
                    val vuelta = cifrador.descifrar(paquete, "clave-de-prueba".toCharArray())
                    val igual = vuelta.contentEquals(original)
                    var rechaza = false
                    try {
                        cifrador.descifrar(paquete, "otra-clave".toCharArray())
                    } catch (e: IllegalStateException) {
                        rechaza = true
                    }
                    val ms = System.currentTimeMillis() - t0
                    if (igual && rechaza) "Cifrado: OK (ida y vuelta correcta, contraseña errónea rechazada, $ms ms)"
                    else "Cifrado: FALLO (igual=$igual, rechaza=$rechaza)"
                } catch (e: Exception) {
                    "Cifrado: ERROR " + e.javaClass.simpleName + " " + (e.message ?: "")
                }
            }
            escribir(r)
        }
    }

    private fun probarInventario() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val apps = AppsDispositivo.cargar(applicationContext)
                    val sb = StringBuilder()
                    sb.append("Inventario: OK, ").append(apps.size).append(" apps. Las 5 con más puntos:")
                    for (a in apps.take(5)) {
                        sb.append("\n · ").append(a.etiqueta).append(" (").append(a.puntos).append(", ").append(a.nivel).append(")")
                    }
                    sb.toString()
                } catch (e: Exception) {
                    "Inventario: ERROR " + e.javaClass.simpleName + " " + (e.message ?: "")
                }
            }
            escribir(r)
        }
    }
}
