package com.vigia.app.collect.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.vigia.app.core.Estado
import com.vigia.app.core.Recoleccion
import com.vigia.app.core.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object A11yEstado {
    /** ¿Está el servicio de accesibilidad de Vigía conectado ahora mismo? */
    val conectado = MutableStateFlow(false)
}

/**
 * Módulo a11y. Solo escucha dos tipos de evento (cambio de ventana y toque) y SOLO anota qué app
 * los produce y cuándo. Está declarado sin permiso para leer el contenido de la pantalla
 * (canRetrieveWindowContent=false), así que no puede ver textos, contraseñas ni mensajes.
 *
 * Es episódico: fuera de una sesión con este módulo, el servicio se apaga solo (disableSelf)
 * y no registra nada. El usuario lo vuelve a activar en Ajustes cuando quiera otra sesión.
 */
class VigiaAccesibilidadService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheUid = ConcurrentHashMap<String, Int>()

    @Volatile
    private var quiere = false
    private var ultimaClave = ""
    private var ultimoTsVentana = 0L
    private var ultimoTsClic = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yEstado.conectado.value = true
        val gestor = SessionManager.obtener(applicationContext)
        scope.launch {
            gestor.cargarSiHaceFalta()
            gestor.estado.collect { ui ->
                if (ui.cargado) {
                    val debe = ui.estado != Estado.INACTIVA && ui.modulos.a11y
                    quiere = debe
                    if (!debe) {
                        Recoleccion.primerPlano = null
                        disableSelf()
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !quiere || !Recoleccion.grabando) return
        val paquete = event.packageName?.toString() ?: return
        if (paquete == packageName) return
        val sink = Recoleccion.sink ?: return
        val ahora = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val clase = event.className?.toString() ?: ""
                if (paquete != "com.android.systemui" && paquete != "android") {
                    Recoleccion.primerPlano = paquete
                }
                val clave = paquete + "|" + clase
                if (clave == ultimaClave && ahora - ultimoTsVentana < 1500L) return
                ultimaClave = clave
                ultimoTsVentana = ahora
                sink.a11y(uidDe(paquete), paquete, "ventana", clase)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (ahora - ultimoTsClic < 250L) return
                ultimoTsClic = ahora
                sink.a11y(uidDe(paquete), paquete, "clic", "")
            }
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        A11yEstado.conectado.value = false
        Recoleccion.primerPlano = null
        scope.cancel()
        super.onDestroy()
    }

    private fun uidDe(paquete: String): Int {
        val guardado = cacheUid[paquete]
        if (guardado != null) return guardado
        val uid = try {
            packageManager.getApplicationInfo(paquete, 0).uid
        } catch (e: Exception) {
            -1
        }
        cacheUid[paquete] = uid
        return uid
    }
}
