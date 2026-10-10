package es.metromadrid.probe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var apiOutput: TextView
    private lateinit var accessibilityOutput: TextView
    private lateinit var apiStatus: TextView
    private lateinit var lineId: EditText
    private lateinit var viaId: EditText
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshAccessibility()
    }

    override fun onResume() {
        super.onResume()
        if (::accessibilityOutput.isInitialized) refreshAccessibility()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFF4F6F8.toInt())
        }
        val title = TextView(this).apply {
            text = "Metro: diagnóstico de trenes por tramo"
            textSize = 22f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(0xFF17283F.toInt())
        }
        page.addView(title, lp(-1, -2))
        page.addView(textView("Objetivo: comprobar si el servicio devuelve trenes asociados a INTERSTATION (origen/destino), sin inferir posiciones a partir de tiempos de llegada." , 14f, 0xFF39485A.toInt()), lp(-1, -2, top = 6))
        page.addView(textView("Privacidad: esta app NO incluye el certificado/clave privada de Metro. La consulta de red es una petición HTTPS normal, sin certificado de cliente ni credenciales. La lectura de Accesibilidad es local y solo se activa manualmente.", 13f, 0xFF7C3D00.toInt()), lp(-1, -2, top = 8, bottom = 10))
        page.addView(section("1 · Probar servicio Tren Digital"), lp(-1, -2, top = 4))
        page.addView(textView("El ID de línea que pide el endpoint puede ser un identificador interno de la app y no necesariamente el número visible de línea.", 13f, 0xFF455468.toInt()), lp(-1, -2, top = 4, bottom = 6))

        val fields = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lineId = EditText(this).apply {
            hint = "lineaId"
            setText("5")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        viaId = EditText(this).apply {
            hint = "viaId"
            setText("1")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        fields.addView(fieldColumn("lineaId", lineId), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        fields.addView(fieldColumn("viaId", viaId), LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(fields, lp(-1, -2))

        val apiButton = Button(this).apply {
            text = "Consultar infoOcupacion (sin credenciales)"
            setOnClickListener { requestEndpoint() }
        }
        page.addView(apiButton, lp(-1, -2, top = 4))
        apiStatus = textView("Estado de red: pendiente. La consulta solo se ejecuta cuando pulsas el botón.", 12f, 0xFF526071.toInt())
        page.addView(apiStatus, lp(-1, -2, top = 4))
        apiOutput = outputBox("Aquí aparecerán el código HTTP/TLS y, si hay JSON, los nodos de tramo detectados.")
        page.addView(apiOutput, lp(-1, 220, top = 4, bottom = 8))
        val apiCopy = Button(this).apply {
            text = "Copiar resultado de red"
            setOnClickListener { copyText("Diagnóstico Tren Digital", apiStatus.text.toString() + "\n" + apiOutput.text.toString()) }
        }
        page.addView(apiCopy, lp(-1, -2))

        page.addView(divider(), lp(-1, dp(1), top = 10, bottom = 8))
        page.addView(section("2 · Leer la interfaz oficial de Metro"), lp(-1, -2))
        page.addView(textView("No detecta posiciones invisibles: solo lee textos/etiquetas que la app oficial exponga en el árbol de Accesibilidad.", 13f, 0xFF455468.toInt()), lp(-1, -2, top = 4, bottom = 4))
        val accButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        accButtons.addView(Button(this).apply {
            text = "Abrir Accesibilidad"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
        accButtons.addView(Button(this).apply {
            text = "Actualizar lectura"
            setOnClickListener { refreshAccessibility() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5) })
        page.addView(accButtons, lp(-1, -2))
        val accCopy = Button(this).apply {
            text = "Copiar lectura de Metro"
            setOnClickListener { copyText("Lectura accesibilidad Metro", accessibilityOutput.text.toString()) }
        }
        page.addView(accCopy, lp(-1, -2))
        accessibilityOutput = outputBox("Aún no hay lectura de Metro.")
        page.addView(accessibilityOutput, lp(-1, 280, top = 4))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page, ViewGroup.LayoutParams(-1, -2))
        }
        setContentView(scroll, ViewGroup.LayoutParams(-1, -1))
    }

    private fun fieldColumn(label: String, field: EditText): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(textView(label, 12f, 0xFF526071.toInt()), lp(-1, -2))
        addView(field, lp(-1, -2))
    }

    private fun section(value: String): TextView = textView(value, 17f, 0xFF1455A3.toInt()).apply {
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun textView(value: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun outputBox(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setTextColor(0xFF17283F.toInt())
        setBackgroundColor(0xFFFFFFFF.toInt())
    }

    private fun divider(): TextView = TextView(this).apply { setBackgroundColor(0xFFD4DCE5.toInt()) }

    private fun lp(width: Int, height: Int, top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height).apply {
            if (top != 0) topMargin = dp(top)
            if (bottom != 0) bottomMargin = dp(bottom)
        }

    private fun requestEndpoint() {
        val line = lineId.text.toString().trim().toIntOrNull()
        val via = viaId.text.toString().trim().toIntOrNull()
        if (line == null || via == null || line < 0 || via < 0) {
            apiStatus.text = "Introduce IDs numéricos no negativos para lineaId y viaId."
            return
        }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(lineId.windowToken, 0)
        apiStatus.text = "Consultando HTTPS…"
        apiOutput.text = "Petición manual en curso. No se envían certificados de cliente ni claves privadas."
        executor.execute {
            val result = fetchEndpoint(line, via)
            runOnUiThread {
                apiStatus.text = result.first
                apiOutput.text = result.second
            }
        }
    }

    private fun fetchEndpoint(line: Int, via: Int): Pair<String, String> {
        val address = "https://serviciosmovilidad.metromadrid.es/tren-digital-rest-services/api/v2_0/coches/infoOcupacion" +
            "?lineaId=$line&viaId=$via&version=2"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "MetroTrenTramoDiagnostic/2.0 (manual test; no client certificate)")
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readLimited(it, 100_000) } ?: "<sin cuerpo de respuesta>"
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val header = "Hora local: $now\nURL: $address\nResultado HTTP: $status\nTamaño cuerpo: ${body.toByteArray(Charsets.UTF_8).size} bytes\n"
            val interpretation = when (status) {
                in 200..299 -> "Respuesta HTTP correcta. Ahora hay que comprobar si contiene nodos de tramo."
                401, 403 -> "El servidor deniega la petición sin una credencial/autorización aceptada; no se intentó eludir esa restricción."
                404 -> "Ruta no encontrada en este host."
                else -> "El servidor devolvió un estado HTTP no satisfactorio."
            }
            val data = if (status in 200..299) parseTrains(body) else ""
            val displayBody = if (status in 200..299 && data.isNotBlank()) data else body.take(7000)
            Pair("$header$interpretation", "--- Datos interpretados / cuerpo de respuesta ---\n$displayBody")
        } catch (e: Exception) {
            val cause = generateSequence(e as Throwable?) { it.cause }.lastOrNull()
            val message = listOfNotNull(e.javaClass.simpleName, e.message, cause?.takeIf { it !== e }?.let { "causa final: ${it.javaClass.simpleName}: ${it.message}" })
                .joinToString("\n")
            Pair("Hora local: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\nFallo de conexión/TLS; no se usó certificado de cliente.",
                "URL: $address\nError: $message\n\nEste resultado solo indica qué ocurre en esta petición sin credenciales desde tu móvil. No demuestra por sí solo que el servicio no exista.")
        } finally {
            conn?.disconnect()
        }
    }

    private fun readLimited(stream: InputStream, maxChars: Int): String {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val out = StringBuilder()
        val buf = CharArray(4096)
        while (out.length < maxChars) {
            val n = reader.read(buf, 0, minOf(buf.size, maxChars - out.length))
            if (n <= 0) break
            out.append(buf, 0, n)
        }
        if (out.length >= maxChars) out.append("\n… respuesta truncada por límite local …")
        return out.toString()
    }

    private fun parseTrains(body: String): String {
        return try {
            val root: Any = when {
                body.trimStart().startsWith("{") -> JSONObject(body)
                body.trimStart().startsWith("[") -> JSONArray(body)
                else -> return "La respuesta HTTP no parece JSON.\n" + body.take(7000)
            }
            val found = mutableListOf<String>()
            findInterstations(root, found, 0)
            if (found.isEmpty()) {
                "HTTP devolvió JSON, pero no se reconoció ningún nodo INTERSTATION con el esquema esperado.\n\n" + body.take(6500)
            } else {
                "Nodos INTERSTATION detectados: ${found.size}\n\n" + found.take(80).joinToString("\n\n") +
                    if (found.size > 80) "\n\n… listado limitado a 80 nodos …" else ""
            }
        } catch (e: Exception) {
            "La petición tuvo respuesta, pero no se pudo interpretar como JSON: ${e.javaClass.simpleName}: ${e.message}\n\n" + body.take(6500)
        }
    }

    private fun findInterstations(value: Any?, out: MutableList<String>, depth: Int) {
        if (depth > 24 || out.size >= 200) return
        when (value) {
            is JSONObject -> {
                val nodeType = value.optString("nodoType", value.optString("nodeType", ""))
                if (nodeType.equals("INTERSTATION", ignoreCase = true)) {
                    val train = value.optJSONObject("train")
                    val origin = firstString(value, "originStationId", "originStation", "origenStationId")
                    val destination = firstString(value, "destinationStationId", "destinationStation", "destinoStationId")
                    val name = train?.let { firstString(it, "trainName", "name", "id") }.orEmpty()
                    val series = train?.let { firstString(it, "series", "trainSeries") }.orEmpty()
                    val via = firstString(value, "via", "viaId")
                    val state = firstString(value, "statusType", "status")
                    out.add("Tramo: ${origin.ifBlank { "(sin origen)" }} → ${destination.ifBlank { "(sin destino)" }}\n" +
                        "Tren: ${name.ifBlank { "(sin identificador)" }}${if (series.isNotBlank()) " · serie $series" else ""}\n" +
                        "Vía: ${via.ifBlank { "(no informado)" }} · Estado: ${state.ifBlank { "(no informado)" }}")
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (child is JSONObject || child is JSONArray) findInterstations(child, out, depth + 1)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                val child = value.opt(i)
                if (child is JSONObject || child is JSONArray) findInterstations(child, out, depth + 1)
            }
        }
    }

    private fun firstString(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = json.opt(key)
            if (value != null && value != JSONObject.NULL && value !is JSONObject && value !is JSONArray) {
                val text = value.toString().trim()
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun refreshAccessibility() {
        if (!::accessibilityOutput.isInitialized) return
        val text = ProbeState.read()
        val at = ProbeState.time()
        val header = if (at == 0L) "Estado: aún no se ha registrado una pantalla de Metro.\n\n" else
            "Última lectura: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))}\n\n"
        accessibilityOutput.text = header + text
    }

    private fun copyText(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(this, "Copiado al portapapeles; revisa el contenido antes de compartirlo.", Toast.LENGTH_LONG).show()
        }
    }
}
