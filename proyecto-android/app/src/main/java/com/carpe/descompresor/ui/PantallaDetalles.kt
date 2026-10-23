package com.carpe.descompresor.ui

import android.widget.EditText
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.carpe.descompresor.apk.EscritorAxml
import com.carpe.descompresor.apk.ParserAxml
import com.carpe.descompresor.data.Entrada
import com.carpe.descompresor.extraer.OpcionesExtraccion
import com.carpe.descompresor.extraer.SiExiste
import com.carpe.descompresor.extraer.SolicitudExtraccion
import com.carpe.descompresor.previsualizacion.Temporales
import com.carpe.descompresor.util.Formato
import com.carpe.descompresor.util.Iconos
import com.carpe.descompresor.util.PreferenciasApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object PantallaDetalles {

    fun textoDetalles(e: Entrada): String {
        val sb = StringBuilder()
        sb.append("Ruta: ").append(e.ruta).append('\n')
        sb.append("Tamaño: ").append(Formato.bytes(e.tamano)).append('\n')
        sb.append("Comprimido: ").append(Formato.bytes(e.tamanoComprimido)).append('\n')
        sb.append("Ratio: ").append(Formato.ratio(e.tamanoComprimido, e.tamano)).append('\n')
        sb.append("Método: ").append(e.metodo).append('\n')
        sb.append("CRC: ").append(if (e.crc >= 0L) String.format("%08X", e.crc) else "—").append('\n')
        sb.append("Fecha: ").append(Formato.fecha(e.fecha)).append('\n')
        if (e.atributos.isNotEmpty()) sb.append("Atributos: ").append(e.atributos).append('\n')
        if (e.cifrada) sb.append("⚠ Cifrada o con método no soportado\n")
        sb.append("Tipo: ").append(e.categoria.etiqueta)
        return sb.toString()
    }

    fun mostrar(activity: AppCompatActivity, vm: ContenedorViewModel, lanz: Lanzadores, n: Nodo) {
        val cont = vm.contenedor ?: return
        val raiz = Ui.vertical(activity, 16)

        if (n.esDir || n.entrada == null) {
            raiz.addView(Ui.monoespaciado(activity, "Carpeta: ${n.ruta}\nArchivos: ${n.archivos.size}\nTamaño: ${Formato.bytes(n.tamano)}", 12f))
            AlertDialog.Builder(activity).setTitle(n.nombre).setView(raiz).setPositiveButton("Cerrar", null).show()
            return
        }
        val e: Entrada = n.entrada
        raiz.addView(Ui.monoespaciado(activity, textoDetalles(e), 12f))
        if (cont.esApk) {
            val c = vm.cambiosApk
            val estado = when {
                e.ruta in c.eliminados -> "Marcada para eliminar"
                c.reemplazos.containsKey(e.ruta) -> "Reemplazada (pendiente de reempaquetar)"
                else -> ""
            }
            if (estado.isNotEmpty()) raiz.addView(Ui.texto(activity, "✎ $estado", 13f, true), Ui.anchoTotal(activity, 6))
        }

        lateinit var dialogo: AlertDialog
        raiz.addView(Ui.boton(activity, "Vista previa") {
            dialogo.dismiss()
            DialogoVistaPrevia.mostrar(activity, vm, e)
        }, Ui.anchoTotal(activity, 8))
        raiz.addView(Ui.botonSecundario(activity, "Extraer solo esto") {
            dialogo.dismiss()
            val p = PreferenciasApp(activity)
            DialogoExtraccion.mostrar(activity, vm, lanz, setOf(e.ruta))
            if (p.bool("modo_dev")) Ui.toast(activity, "Extrayendo ${e.ruta}")
        })

        if (e.categoria == Iconos.Categoria.COMPRIMIDO || e.categoria == Iconos.Categoria.APK) {
            raiz.addView(Ui.boton(activity, "Abrir como contenedor") {
                dialogo.dismiss()
                vm.abrirAnidado(activity, e)
            }, Ui.anchoTotal(activity, 4))
        }

        if (cont.esApk) {
            raiz.addView(Ui.botonSecundario(activity, "APK: reemplazar con archivo…") {
                dialogo.dismiss()
                lanz.abrirDocumento { uri ->
                    if (uri == null) return@abrirDocumento
                    activity.lifecycleScope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) {
                                val d = File(activity.cacheDir, "cambios_apk")
                                d.mkdirs()
                                val destino = File(d, "r_" + System.currentTimeMillis())
                                val i = activity.contentResolver.openInputStream(uri) ?: throw java.io.IOException("No se pudo abrir")
                                i.use { s -> destino.outputStream().use { o -> s.copyTo(o) } }
                                destino
                            }
                            vm.cambiosApk.eliminados.remove(e.ruta)
                            vm.cambiosApk.reemplazos[e.ruta]?.delete()
                            vm.cambiosApk.reemplazos[e.ruta] = f
                            vm.notificar()
                            Ui.toast(activity, "Reemplazo registrado. Ve a la pestaña APK para reempaquetar y firmar.")
                        } catch (t: Throwable) {
                            Ui.toast(activity, "Error: " + Ui.mensajeError(t))
                        }
                    }
                }
            })
            raiz.addView(Ui.botonSecundario(activity, "APK: editar como texto…") {
                dialogo.dismiss()
                editar(activity, vm, e)
            })
            raiz.addView(Ui.botonSecundario(activity, if (e.ruta in vm.cambiosApk.eliminados) "APK: no eliminar" else "APK: eliminar del paquete") {
                dialogo.dismiss()
                if (e.ruta in vm.cambiosApk.eliminados) vm.cambiosApk.eliminados.remove(e.ruta)
                else {
                    vm.cambiosApk.eliminados.add(e.ruta)
                    vm.cambiosApk.reemplazos.remove(e.ruta)?.delete()
                }
                vm.notificar()
            })
        }

        val sv = ScrollView(activity)
        sv.addView(raiz)
        dialogo = AlertDialog.Builder(activity)
            .setTitle(e.nombre)
            .setView(sv)
            .setNegativeButton("Cerrar", null)
            .create()
        dialogo.show()
    }

    fun editar(activity: AppCompatActivity, vm: ContenedorViewModel, e: Entrada) {
        val cont = vm.contenedor ?: return
        val limite = 512 * 1024
        if (e.tamano > limite) {
            Ui.toast(activity, "Archivo demasiado grande para editar aquí (máx. 512 KB)")
            return
        }
        activity.lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val rep = vm.cambiosApk.reemplazos[e.ruta]
                    if (rep != null) rep.readBytes() else Temporales.leerPrimeros(cont, e, limite)
                }
                val esAxml = ParserAxml.esAxml(bytes)
                val textoInicial: String = if (esAxml) {
                    try {
                        ParserAxml.parsear(bytes).xml
                    } catch (t: Throwable) {
                        Ui.toast(activity, "No se pudo decodificar el XML binario: " + Ui.mensajeError(t))
                        return@launch
                    }
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                val campo = EditText(activity)
                campo.setText(textoInicial)
                campo.typeface = android.graphics.Typeface.MONOSPACE
                campo.textSize = 12f * Ui.escala
                campo.setHorizontallyScrolling(true)
                campo.gravity = android.view.Gravity.TOP
                campo.minLines = 12
                val sv = ScrollView(activity)
                sv.addView(campo)
                AlertDialog.Builder(activity)
                    .setTitle((if (esAxml) "Editar (XML binario) " else "Editar ") + e.nombre)
                    .setView(sv)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Guardar cambio") { _, _ ->
                        try {
                            val d = File(activity.cacheDir, "cambios_apk")
                            d.mkdirs()
                            val f = File(d, "t_" + System.currentTimeMillis())
                            val textoNuevo = campo.text.toString()
                            if (esAxml) {
                                f.writeBytes(EscritorAxml.compilar(textoNuevo, bytes))
                            } else {
                                f.writeBytes(textoNuevo.toByteArray(Charsets.UTF_8))
                            }
                            vm.cambiosApk.reemplazos[e.ruta]?.delete()
                            vm.cambiosApk.eliminados.remove(e.ruta)
                            vm.cambiosApk.reemplazos[e.ruta] = f
                            vm.notificar()
                            Ui.toast(activity, "Cambio registrado. Reempaqueta desde la pestaña APK.")
                        } catch (t: Throwable) {
                            AlertDialog.Builder(activity)
                                .setTitle("No se pudo guardar el cambio")
                                .setMessage(Ui.mensajeError(t))
                                .setPositiveButton("Cerrar", null)
                                .show()
                        }
                    }
                    .show()
            } catch (t: Throwable) {
                Ui.toast(activity, "Error: " + Ui.mensajeError(t))
            }
        }
    }
}
