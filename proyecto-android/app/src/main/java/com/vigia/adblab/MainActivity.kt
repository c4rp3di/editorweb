package com.vigia.adblab

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.vigia.adb.Comandos
import com.vigia.adb.EstadoShell
import com.vigia.adb.ShellManager
import com.vigia.adb.ShizukuShell
import com.vigia.core.R
import com.vigia.core.ui.ActividadBase
import com.vigia.core.ui.TipoBoton
import com.vigia.core.ui.Vistas
import kotlinx.coroutines.launch

/** Pantalla de verificación del módulo :vigia-adb: estado de cada proveedor y ejecución de comandos reales. */
class MainActivity : ActividadBase() {

    private lateinit var gestor: ShellManager
    private lateinit var estadoTexto: TextView
    private lateinit var salida: TextView
    private lateinit var campo: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gestor = ShellManager(this)
        construirUi()
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    private fun describir(e: EstadoShell): String {
        return when (e) {
            EstadoShell.LISTO -> "LISTO ✔"
            EstadoShell.SIN_PERMISO -> "falta autorizar esta app"
            EstadoShell.NO_ACTIVO -> "instalado/configurado pero no está en marcha"
            EstadoShell.NO_INSTALADO -> "no instalado"
            EstadoShell.NO_DISPONIBLE -> "no disponible en este teléfono"
        }
    }

    private fun construirUi() {
        val raiz = Vistas.columna(this, 16)
        raiz.addView(Vistas.titulo(this, "ADB Lab"), Vistas.anchoCompleto(this, 2))
        raiz.addView(Vistas.texto(this, ":vigia-adb · entrega 0B · shell con privilegios sin PC", true), Vistas.anchoCompleto(this, 14))

        val estado = Vistas.tarjeta(this)
        estado.addView(Vistas.subtitulo(this, "Estado"), Vistas.anchoCompleto(this, 6))
        estadoTexto = Vistas.texto(this, "Comprobando…")
        estado.addView(estadoTexto, Vistas.anchoCompleto(this, 8))
        estado.addView(Vistas.boton(this, "Volver a comprobar", TipoBoton.TONAL) { refrescar() }, Vistas.anchoCompleto(this, 0))
        raiz.addView(estado, Vistas.anchoCompleto(this, 12))

        val sh = Vistas.tarjeta(this)
        sh.addView(Vistas.subtitulo(this, "Opción A · Shizuku (sin PC)"), Vistas.anchoCompleto(this, 6))
        sh.addView(
            Vistas.texto(
                this,
                "1) Instala Shizuku (shizuku.rikka.app). 2) En Ajustes › Opciones de desarrollador activa «Depuración inalámbrica». " +
                    "3) En Shizuku pulsa «Iniciar mediante depuración inalámbrica» y empareja con el código. 4) Vuelve aquí y pide el permiso.",
                true
            ),
            Vistas.anchoCompleto(this, 8)
        )
        sh.addView(Vistas.boton(this, "Abrir Shizuku", TipoBoton.TONAL) { abrirShizuku() }, Vistas.anchoCompleto(this, 8))
        sh.addView(Vistas.boton(this, "Pedir permiso a Shizuku", TipoBoton.TONAL) { pedirPermisoShizuku() }, Vistas.anchoCompleto(this, 0))
        raiz.addView(sh, Vistas.anchoCompleto(this, 12))

        val adb = Vistas.tarjeta(this)
        adb.addView(Vistas.subtitulo(this, "Opción B · ADB local (cliente propio)"), Vistas.anchoCompleto(this, 6))
        adb.addView(
            Vistas.texto(
                this,
                "Requiere que adbd escuche en el puerto 5555. Desde un PC, una vez: «adb tcpip 5555». Para que sobreviva a los reinicios: " +
                    "«adb shell setprop persist.adb.tcp.port 5555». Después pulsa «Autorizar» y acepta el diálogo del teléfono con «Permitir siempre».",
                true
            ),
            Vistas.anchoCompleto(this, 8)
        )
        adb.addView(Vistas.boton(this, "Autorizar ADB local", TipoBoton.TONAL) { autorizarAdb() }, Vistas.anchoCompleto(this, 0))
        raiz.addView(adb, Vistas.anchoCompleto(this, 12))

        val prueba = Vistas.tarjeta(this)
        prueba.addView(Vistas.subtitulo(this, "Probar comandos"), Vistas.anchoCompleto(this, 6))
        campo = EditText(this)
        campo.setText("id")
        campo.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        campo.setTextColor(Vistas.color(this, R.color.vc_texto))
        prueba.addView(campo, Vistas.anchoCompleto(this, 6))
        prueba.addView(Vistas.boton(this, "Ejecutar") { ejecutar(campo.text.toString()) }, Vistas.anchoCompleto(this, 6))
        prueba.addView(Vistas.boton(this, "Identidad (id)", TipoBoton.TONAL) { ejecutar("id") }, Vistas.anchoCompleto(this, 6))
        prueba.addView(Vistas.boton(this, "Apps instaladas (pm list packages -3)", TipoBoton.TONAL) { ejecutar("pm list packages -3") }, Vistas.anchoCompleto(this, 6))
        prueba.addView(Vistas.boton(this, "Batería (parseada)", TipoBoton.TONAL) { bateria() }, Vistas.anchoCompleto(this, 6))
        prueba.addView(Vistas.boton(this, "Procesos: top 10 por memoria", TipoBoton.TONAL) { procesos() }, Vistas.anchoCompleto(this, 0))
        raiz.addView(prueba, Vistas.anchoCompleto(this, 12))

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

    private fun refrescar() {
        lifecycleScope.launch {
            val s = try {
                gestor.shizuku.estado()
            } catch (e: Exception) {
                EstadoShell.NO_DISPONIBLE
            }
            val a = try {
                gestor.adbLocal.estado()
            } catch (e: Exception) {
                EstadoShell.NO_DISPONIBLE
            }
            val mejor = gestor.detectar()
            estadoTexto.text = "Shizuku: " + describir(s) + "\nADB local: " + describir(a) + "\n\nProveedor en uso: " + (mejor?.nombre ?: "ninguno")
        }
    }

    private fun abrirShizuku() {
        val intento = packageManager.getLaunchIntentForPackage(ShizukuShell.PAQUETE_SHIZUKU)
        if (intento != null) {
            startActivity(intento)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
        }
    }

    private fun pedirPermisoShizuku() {
        lifecycleScope.launch {
            val ok = gestor.shizuku.pedirPermiso()
            escribir(if (ok) "Shizuku: permiso concedido." else "Shizuku: permiso NO concedido (¿está en marcha?).")
            refrescar()
        }
    }

    private fun autorizarAdb() {
        escribir("ADB local: esperando tu confirmación en el diálogo de Android (hasta 30 s)…")
        lifecycleScope.launch {
            val e = gestor.adbLocal.autorizar()
            escribir("ADB local: " + describir(e))
            refrescar()
        }
    }

    private fun ejecutar(comando: String) {
        if (comando.isBlank()) return
        lifecycleScope.launch {
            escribir("$ " + comando)
            try {
                val r = gestor.ejecutar(comando)
                if (r == null) {
                    escribir("Ningún proveedor está listo. Configura Shizuku o ADB local arriba.")
                } else {
                    escribir(r.salida.take(4000))
                    if (r.error.isNotEmpty()) escribir("[stderr] " + r.error.take(1000))
                    escribir("[código " + r.codigo + " · " + (gestor.activo.value?.nombre ?: "?") + "]")
                }
            } catch (e: Exception) {
                escribir("Error: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun bateria() {
        lifecycleScope.launch {
            val p = gestor.detectar()
            if (p == null) {
                escribir("Ningún proveedor está listo.")
                return@launch
            }
            try {
                val b = Comandos(p).bateria()
                escribir("Batería: " + (b["level"] ?: "?") + " % · estado " + (b["status"] ?: "?") + " · " +
                    ((b["temperature"]?.toIntOrNull() ?: 0) / 10.0) + " °C · " + (b["voltage"] ?: "?") + " mV")
            } catch (e: Exception) {
                escribir("Error: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun procesos() {
        lifecycleScope.launch {
            val p = gestor.detectar()
            if (p == null) {
                escribir("Ningún proveedor está listo.")
                return@launch
            }
            try {
                val lista = Comandos(p).procesos().sortedByDescending { it.rssKb }.take(10)
                escribir("Procesos: " + lista.size + " (top por memoria)")
                for (x in lista) escribir(" · " + x.nombre + "  " + (x.rssKb / 1024) + " MB  (" + x.usuario + ")")
            } catch (e: Exception) {
                escribir("Error: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }
}
