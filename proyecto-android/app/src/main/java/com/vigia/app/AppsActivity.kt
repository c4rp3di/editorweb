package com.vigia.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.vigia.app.core.SessionManager
import com.vigia.app.integridad.AppInfo
import com.vigia.app.integridad.AppsDispositivo
import com.vigia.app.util.Formato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gestor de apps: lista en vivo con una puntuación visible, detalles, su actividad en la última sesión y atajos
 * para desinstalar (apps de usuario, con la confirmación de Android) o abrir sus ajustes.
 */
class AppsActivity : AppCompatActivity() {

    private lateinit var gestor: SessionManager
    private lateinit var etBuscar: EditText
    private lateinit var spFiltro: Spinner
    private lateinit var resumen: TextView
    private lateinit var adaptador: ArrayAdapter<AppInfo>
    private var todas: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        gestor = SessionManager.obtener(applicationContext)

        etBuscar = findViewById(R.id.etBuscarApp)
        spFiltro = findViewById(R.id.spFiltroApps)
        resumen = findViewById(R.id.appsResumen)
        val lista = findViewById<ListView>(R.id.listaApps)

        adaptador = object : ArrayAdapter<AppInfo>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val vista = super.getView(position, convertView, parent)
                val a = getItem(position)
                val t1 = vista.findViewById<TextView>(android.R.id.text1)
                val t2 = vista.findViewById<TextView>(android.R.id.text2)
                if (a != null) {
                    val marca = if (a.nivel == "revisar") "● REVISAR · " else if (a.nivel == "atencion") "○ atención · " else ""
                    t1.text = marca + a.etiqueta
                    val tipo = if (a.sistema) "sistema" else "usuario"
                    val extra = if (a.motivos.isEmpty()) "" else "\n" + a.motivos.take(3).joinToString(" · ")
                    t2.text = a.paquete + " · " + tipo + extra
                }
                return vista
            }
        }
        lista.adapter = adaptador

        val adaptadorFiltro = ArrayAdapter.createFromResource(this, R.array.filtro_apps, android.R.layout.simple_spinner_item)
        adaptadorFiltro.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spFiltro.adapter = adaptadorFiltro
        spFiltro.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(padre: AdapterView<*>?, vista: View?, posicion: Int, id: Long) {
                aplicar()
            }

            override fun onNothingSelected(padre: AdapterView<*>?) {
            }
        }
        etBuscar.doAfterTextChanged { aplicar() }
        findViewById<MaterialButton>(R.id.btnVolver).setOnClickListener { finish() }
        lista.setOnItemClickListener { _, _, posicion, _ ->
            val a = adaptador.getItem(posicion)
            if (a != null) mostrarAcciones(a)
        }
    }

    override fun onResume() {
        super.onResume()
        cargar()
    }

    private fun cargar() {
        resumen.setText(R.string.cargando_apps)
        lifecycleScope.launch {
            val lista = withContext(Dispatchers.IO) { AppsDispositivo.cargar(applicationContext) }
            todas = lista
            aplicar()
        }
    }

    private fun aplicar() {
        val texto = etBuscar.text.toString().trim().lowercase()
        val filtro = spFiltro.selectedItemPosition
        val filtradas = todas.filter { a ->
            val pasaFiltro = when (filtro) {
                1 -> !a.sistema
                2 -> a.sistema
                3 -> a.nivel != "normal"
                else -> true
            }
            pasaFiltro && (texto.isEmpty() || a.etiqueta.lowercase().contains(texto) || a.paquete.lowercase().contains(texto))
        }
        adaptador.setNotifyOnChange(false)
        adaptador.clear()
        adaptador.addAll(filtradas)
        adaptador.notifyDataSetChanged()
        val revisar = todas.count { it.nivel == "revisar" }
        val atencion = todas.count { it.nivel == "atencion" }
        resumen.text = filtradas.size.toString() + " de " + todas.size + " apps · para revisar: " + revisar + " · atención: " + atencion
    }

    private fun mostrarAcciones(a: AppInfo) {
        val etiquetas = ArrayList<String>()
        val acciones = ArrayList<() -> Unit>()

        etiquetas.add("Ver detalles y por qué puntúa así")
        acciones.add { detalles(a) }

        val ui = gestor.estado.value
        val sesion = ui.sesionId ?: ui.ultimaId
        if (sesion != null) {
            etiquetas.add("Ver su actividad en la sesión #" + sesion)
            acciones.add { abrirLinea(sesion, a.paquete) }
        }
        etiquetas.add("Abrir sus ajustes (permisos, desactivar…)")
        acciones.add { abrirAjustes(a.paquete) }
        if (!a.sistema) {
            etiquetas.add("Desinstalar")
            acciones.add { confirmarDesinstalar(a) }
        }
        if (a.accesibilidad) {
            etiquetas.add("Ir a los ajustes de accesibilidad")
            acciones.add { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        etiquetas.add("Copiar el nombre del paquete")
        acciones.add {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("paquete", a.paquete))
            Toast.makeText(this, R.string.copiado, Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(this)
            .setTitle(a.etiqueta)
            .setItems(etiquetas.toTypedArray()) { _, cual -> acciones[cual]() }
            .show()
    }

    private fun detalles(a: AppInfo) {
        val sb = StringBuilder()
        sb.append(a.paquete).append('\n')
        sb.append(if (a.sistema) "App del sistema" else "App de usuario")
        if (a.actualizadaDeSistema) sb.append(" (con actualizaciones)")
        sb.append(if (a.habilitada) "" else " · DESACTIVADA").append('\n')
        sb.append("Versión: ").append(if (a.version.isEmpty()) "?" else a.version).append('\n')
        if (a.instalada > 0L) sb.append("Instalada: ").append(Formato.fecha(a.instalada)).append('\n')
        if (a.actualizada > 0L) sb.append("Última actualización: ").append(Formato.fecha(a.actualizada)).append('\n')
        sb.append("Instalada por: ").append(if (a.instalador.isEmpty()) "desconocido" else a.instalador).append('\n')
        if (a.huella.isNotEmpty()) sb.append("Huella de la firma: ").append(a.huella).append('\n')
        sb.append("Icono para abrirla: ").append(if (a.conLanzador) "sí" else "no").append('\n')
        if (a.permisos.isNotEmpty()) sb.append("Permisos sensibles concedidos: ").append(a.permisos.joinToString(", ")).append('\n')
        if (a.especiales.isNotEmpty()) sb.append("Permisos especiales que declara: ").append(a.especiales.joinToString(", ")).append('\n')
        val roles = ArrayList<String>()
        if (a.accesibilidad) roles.add("accesibilidad")
        if (a.lectorNotificaciones) roles.add("lector de notificaciones")
        if (a.administrador) roles.add("administradora del dispositivo")
        if (a.teclado) roles.add("teclado activo")
        if (roles.isNotEmpty()) sb.append("Con acceso especial: ").append(roles.joinToString(", ")).append('\n')
        sb.append("\nPuntuación de revisión: ").append(a.puntos).append('\n')
        if (a.motivos.isEmpty()) {
            sb.append("Sin motivos para revisarla.\n")
        } else {
            for (m in a.motivos) sb.append("• ").append(m).append('\n')
        }
        sb.append("\nLa puntuación solo ordena la lista: que una app puntúe alto no significa que sea maliciosa.")
        if (a.sistema) {
            sb.append("\n\nLas apps del sistema no se pueden desinstalar sin ADB. Si Android lo permite, puedes desactivarla desde sus ajustes.")
        }
        AlertDialog.Builder(this).setTitle(a.etiqueta).setMessage(sb.toString()).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun abrirLinea(sesion: Long, paquete: String) {
        val intent = Intent(this, LineaTiempoActivity::class.java)
        intent.putExtra(SessionDetailActivity.EXTRA_SESION, sesion)
        intent.putExtra(LineaTiempoActivity.EXTRA_APP, paquete)
        startActivity(intent)
    }

    private fun abrirAjustes(paquete: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + paquete)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_se_pudo_abrir, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmarDesinstalar(a: AppInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.desinstalar)
            .setMessage(getString(R.string.confirmar_desinstalar, a.etiqueta))
            .setPositiveButton(R.string.desinstalar) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.paquete)))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.no_se_pudo_abrir, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
