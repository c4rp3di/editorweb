package com.vigia.boveda

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.core.content.IntentCompat
import com.vigia.boveda.R
import com.vigia.core.red.UtilRed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Lo que el servicio encontró en la pantalla de otra app o página: los campos y de dónde viene. */
class CamposAutofill(
    val usuarioId: AutofillId?,
    val claveId: AutofillId?,
    val dominioWeb: String?,
    val paquete: String,
    val usuarioValor: String,
    val claveValor: String
) {
    val hayCampos: Boolean get() = usuarioId != null || claveId != null
}

/** Paso de los campos al diálogo de desbloqueo y vuelta. */
object ExtrasAutofill {
    const val ES_AUTOFILL = "es_autofill"
    private const val USUARIO = "usuario_id"
    private const val CLAVE = "clave_id"
    private const val DOMINIO = "dominio"
    private const val PAQUETE = "paquete"

    fun poner(i: Intent, c: CamposAutofill) {
        i.putExtra(ES_AUTOFILL, true)
        i.putExtra(USUARIO, c.usuarioId)
        i.putExtra(CLAVE, c.claveId)
        i.putExtra(DOMINIO, c.dominioWeb)
        i.putExtra(PAQUETE, c.paquete)
    }

    fun leer(i: Intent): CamposAutofill {
        return CamposAutofill(
            IntentCompat.getParcelableExtra(i, USUARIO, AutofillId::class.java),
            IntentCompat.getParcelableExtra(i, CLAVE, AutofillId::class.java),
            i.getStringExtra(DOMINIO),
            i.getStringExtra(PAQUETE) ?: "",
            "", ""
        )
    }
}

/** Recorre la estructura de la pantalla y localiza el campo de usuario y el de contraseña. */
object ParserEstructura {

    private class Nodo(val id: AutofillId, val esClave: Boolean, val esUsuario: Boolean, val enfocado: Boolean, val valor: String)

    fun analizar(estructura: AssistStructure): CamposAutofill {
        val nodos = ArrayList<Nodo>()
        val dominios = ArrayList<String>()
        for (i in 0 until estructura.windowNodeCount) {
            recorrer(estructura.getWindowNodeAt(i).rootViewNode, nodos, dominios)
        }
        val paquete = estructura.activityComponent?.packageName ?: ""
        val clave = nodos.firstOrNull { it.esClave && it.enfocado } ?: nodos.firstOrNull { it.esClave }
        var usuario: Nodo? = null
        if (clave != null) {
            // El usuario es el último campo de texto candidato anterior a la contraseña.
            for (n in nodos) {
                if (n === clave) break
                if (n.esUsuario && !n.esClave) usuario = n
            }
        }
        if (usuario == null) usuario = nodos.firstOrNull { it.esUsuario && !it.esClave }
        return CamposAutofill(
            usuario?.id, clave?.id, dominios.firstOrNull { it.isNotEmpty() }, paquete,
            usuario?.valor ?: "", clave?.valor ?: ""
        )
    }

    private fun recorrer(n: AssistStructure.ViewNode, salida: MutableList<Nodo>, dominios: MutableList<String>) {
        val web = n.webDomain
        if (!web.isNullOrEmpty()) dominios.add(web.lowercase())
        val id = n.autofillId
        if (id != null && n.visibility == View.VISIBLE && n.autofillType == View.AUTOFILL_TYPE_TEXT) {
            val pistas = n.autofillHints?.map { it.lowercase() } ?: emptyList()
            val tipo = n.inputType
            val variacion = tipo and InputType.TYPE_MASK_VARIATION
            val esTexto = (tipo and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
            val etiqueta = ((n.idEntry ?: "") + " " + (n.hint ?: "")).lowercase()

            val porPista = pistas.contains(View.AUTOFILL_HINT_PASSWORD)
            val porTipo = esTexto && (variacion == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variacion == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variacion == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            val porEtiqueta = etiqueta.contains("pass") || etiqueta.contains("contraseña") || etiqueta.contains("clave")
            val esClave = porPista || porTipo || (porEtiqueta && !pistas.contains(View.AUTOFILL_HINT_USERNAME))

            val usuarioPista = pistas.contains(View.AUTOFILL_HINT_USERNAME) || pistas.contains(View.AUTOFILL_HINT_EMAIL_ADDRESS)
            val usuarioTipo = esTexto && (variacion == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variacion == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
            val usuarioEtiqueta = etiqueta.contains("user") || etiqueta.contains("email") || etiqueta.contains("mail") ||
                etiqueta.contains("login") || etiqueta.contains("usuario") || etiqueta.contains("correo")
            val esUsuario = !esClave && (usuarioPista || usuarioTipo || usuarioEtiqueta)

            if (esClave || esUsuario) {
                val v = n.autofillValue
                val texto = if (v != null && v.isText) v.textValue.toString() else ""
                salida.add(Nodo(id, esClave, esUsuario, n.isFocused, texto))
            }
        }
        for (i in 0 until n.childCount) recorrer(n.getChildAt(i), salida, dominios)
    }
}

/** Decide qué credenciales encajan con un formulario y construye la respuesta para el sistema. */
object ConstructorRespuesta {

    /**
     * Coincidencia estricta: el sitio web debe ser el mismo dominio guardado o un subdominio suyo (o al revés);
     * en apps, el paquete debe ser el vinculado. Nunca se sugiere por parecido de nombre.
     */
    fun encaja(c: Credencial, dominioWeb: String?, paquete: String): Boolean {
        if (!dominioWeb.isNullOrEmpty()) {
            val host = UtilRed.extraerHost(c.url) ?: return false
            return dominioWeb == host || dominioWeb.endsWith("." + host) || host.endsWith("." + dominioWeb)
        }
        return c.appPaquete.isNotEmpty() && c.appPaquete == paquete
    }

    private fun vista(c: Context, titulo: String, sub: String): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.autofill_item)
        v.setTextViewText(R.id.autofill_titulo, titulo)
        v.setTextViewText(R.id.autofill_sub, sub)
        return v
    }

    private fun idsDe(campos: CamposAutofill): Array<AutofillId> = listOfNotNull(campos.usuarioId, campos.claveId).toTypedArray()

    /** Bóveda cerrada: una sola entrada «Desbloquear» que abre la pantalla de desbloqueo. */
    fun pedirDesbloqueo(c: Context, campos: CamposAutofill): FillResponse {
        val intent = Intent(c, DesbloqueoActivity::class.java)
        ExtrasAutofill.poner(intent, campos)
        val pendiente = PendingIntent.getActivity(c, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        return FillResponse.Builder()
            .setAuthentication(idsDe(campos), pendiente.intentSender, vista(c, "La Bóveda", "Toca para desbloquear"))
            .build()
    }

    /** Bóveda abierta: un dato por credencial que encaje, y la opción de guardar lo que se escriba. */
    suspend fun conDatos(c: Context, campos: CamposAutofill): FillResponse? {
        val dao = BovedaDb.actual()?.credenciales() ?: return null
        val b = FillResponse.Builder()
        var hay = false
        for (cred in dao.todas()) {
            if (!encaja(cred, campos.dominioWeb, campos.paquete)) continue
            val d = Dataset.Builder(vista(c, cred.titulo, cred.usuario))
            var valores = 0
            if (campos.usuarioId != null) {
                d.setValue(campos.usuarioId, AutofillValue.forText(cred.usuario))
                valores++
            }
            if (campos.claveId != null) {
                d.setValue(campos.claveId, AutofillValue.forText(cred.clave))
                valores++
            }
            if (valores > 0) {
                b.addDataset(d.build())
                hay = true
            }
        }
        if (campos.claveId != null) {
            b.setSaveInfo(SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, idsDe(campos)).build())
            hay = true
        }
        return if (hay) b.build() else null
    }
}

class BovedaAutofillService : AutofillService() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        ambito.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(peticion: FillRequest, cancelacion: CancellationSignal, callback: FillCallback) {
        val estructura = peticion.fillContexts.last().structure
        val campos = ParserEstructura.analizar(estructura)
        // No se ofrece nada dentro de La Bóveda misma, ni donde no hay campos de acceso.
        if (!campos.hayCampos || campos.paquete == packageName) {
            callback.onSuccess(null)
            return
        }
        if (!SesionBoveda.desbloqueada) {
            callback.onSuccess(ConstructorRespuesta.pedirDesbloqueo(this, campos))
            return
        }
        val trabajo: Job = ambito.launch {
            try {
                callback.onSuccess(ConstructorRespuesta.conDatos(applicationContext, campos))
            } catch (e: Exception) {
                callback.onSuccess(null)
            }
        }
        cancelacion.setOnCancelListener { trabajo.cancel() }
    }

    override fun onSaveRequest(peticion: SaveRequest, callback: SaveCallback) {
        val estructura = peticion.fillContexts.last().structure
        val campos = ParserEstructura.analizar(estructura)
        if (campos.claveValor.isEmpty()) {
            callback.onSuccess()
            return
        }
        if (!SesionBoveda.desbloqueada) {
            callback.onFailure("La Bóveda está bloqueada. Desbloquéala y guarda la contraseña desde la app.")
            return
        }
        ambito.launch {
            try {
                val dao = BovedaDb.actual()?.credenciales()
                if (dao == null) {
                    callback.onFailure("La Bóveda está bloqueada.")
                    return@launch
                }
                val ahora = System.currentTimeMillis()
                val existente = dao.todas().firstOrNull { it.usuario == campos.usuarioValor && ConstructorRespuesta.encaja(it, campos.dominioWeb, campos.paquete) }
                if (existente != null) {
                    dao.actualizar(existente.copy(clave = campos.claveValor, modificado = ahora))
                } else {
                    val titulo = campos.dominioWeb ?: campos.paquete
                    dao.insertar(
                        Credencial(
                            titulo = titulo, usuario = campos.usuarioValor, clave = campos.claveValor,
                            url = if (campos.dominioWeb != null) "https://" + campos.dominioWeb else "",
                            appPaquete = if (campos.dominioWeb == null) campos.paquete else "",
                            notas = "", favorito = false, creado = ahora, modificado = ahora
                        )
                    )
                }
                callback.onSuccess()
            } catch (e: Exception) {
                callback.onFailure("No se pudo guardar: " + e.javaClass.simpleName)
            }
        }
    }
}
