package com.vigia.app.analyze

import android.content.Context

class ReglaInfo(
    val id: String,
    val titulo: String,
    val descripcion: String,
    val queEs: String,
    val porQue: String,
    val queHacer: String,
    val defecto: Boolean
)

/** Catálogo de reglas: todas son visibles y se pueden activar o desactivar una a una. */
object Reglas {

    val todas: List<ReglaInfo> = listOf(
        ReglaInfo(
            "SIG", "Coincidencia con indicadores conocidos",
            "Compara dominios, IPs y nombres de paquete con las listas de indicadores (MVT, Amnistía Internacional, etc.).",
            "Algo de lo registrado coincide con un indicador publicado por investigadores.",
            "Esas listas recogen infraestructura y apps asociadas a spyware o stalkerware conocidos. Una coincidencia es la señal más fuerte que puede dar esta herramienta, pero no es una prueba por sí sola: un dominio compartido puede dar falsos positivos.",
            "No borres nada. Anota el nombre de la app o dominio, guarda la sesión y consulta a una organización de apoyo (por ejemplo la línea de ayuda de Access Now o el Security Lab de Amnistía). Si es una app que no reconoces, revisa sus permisos.",
            true
        ),
        ReglaInfo(
            "BASE", "Actividad fuera de lo habitual",
            "Compara cada hora de la sesión con tus 14 días anteriores (percentiles 5 y 95 y más de 2 desviaciones).",
            "Hubo mucha más o mucha menos actividad de lo normal en esa franja.",
            "Un cambio brusco puede deberse a algo inocente (un viaje, una app nueva, una actualización) o a algo que actúa a escondidas.",
            "Mira qué hiciste a esa hora y qué apps estaban activas en la línea de tiempo. Si hay una explicación, ignóralo.",
            true
        ),
        ReglaInfo(
            "CORR", "Varias señales coinciden",
            "Sube la gravedad cuando varios módulos ven algo raro en la misma ventana de tiempo o sobre la misma app.",
            "Distintas señales débiles ocurrieron casi a la vez.",
            "Una señal aislada suele ser ruido; varias juntas, sobre todo de módulos distintos, son más difíciles de explicar por casualidad.",
            "Abre el hallazgo y pulsa «Ver en la línea de tiempo» para ver qué pasó en esos minutos.",
            true
        ),
        ReglaInfo(
            "H1", "Consultas nocturnas sin interacción",
            "Una app consulta dominios de madrugada (por defecto 02:00 a 05:00), con la pantalla apagada y sin que toques el teléfono en los 10 minutos previos.",
            "Una app se conectó mientras no mirabas el teléfono.",
            "Muchas apps sincronizan de noche de forma legítima, pero el espionaje suele actuar cuando no estás mirando.",
            "Mira qué app es y a qué dominios consultó. Si la reconoces (correo, nube, mensajería), probablemente es normal. Si no, revisa sus permisos o desinstálala.",
            true
        ),
        ReglaInfo(
            "H2", "Dominio nuevo de una app sin interfaz",
            "Una app sin icono de lanzador contacta un dominio que no se había visto en sesiones anteriores.",
            "Una app que no se abre normalmente habló con un destino nuevo.",
            "Es habitual en apps de sistema y servicios, por eso viene desactivada por defecto.",
            "Búscala en los ajustes de apps. Si es del sistema o de tu fabricante, suele ser normal.",
            false
        ),
        ReglaInfo(
            "H3", "Accesibilidad activa sin uso visible",
            "Una app de usuario con servicio de accesibilidad activo nunca aparece en pantalla durante la sesión.",
            "Hay una app con acceso de accesibilidad que no se ve en uso.",
            "La accesibilidad permite leer la pantalla y pulsar botones; es lo que más abusan el stalkerware y los troyanos bancarios.",
            "Revisa en Ajustes › Accesibilidad si la reconoces y la necesitas. Si no, desactívala y desinstala la app.",
            true
        ),
        ReglaInfo(
            "H4", "Cámara o micrófono con la pantalla apagada",
            "La cámara o el micrófono pasan a estar en uso mientras la pantalla está apagada (se ignoran las llamadas).",
            "Algo usó la cámara o el micrófono sin que miraras el teléfono.",
            "Un uso legítimo con la pantalla apagada es raro: grabadoras, asistentes de voz o llamadas. El resto merece revisión.",
            "Piensa si estabas grabando, usando el asistente o en una llamada. Si no, revisa qué apps tienen permiso de cámara o micrófono.",
            true
        ),
        ReglaInfo(
            "H5", "Certificado instalado por el usuario",
            "Aparece un certificado de usuario nuevo o cambia el almacén de certificados.",
            "Se instaló un certificado de confianza adicional en el teléfono.",
            "Un certificado extra puede permitir que alguien lea tu tráfico cifrado. Lo usan algunas empresas y herramientas de depuración, pero también el espionaje.",
            "Si no lo instalaste tú, quítalo en Ajustes › Seguridad › Credenciales de usuario.",
            true
        ),
        ReglaInfo(
            "H6", "Administrador del dispositivo activado",
            "Una app obtiene permisos de administrador del dispositivo.",
            "Una app puede ahora bloquear, borrar o impedir que la desinstales.",
            "Algunas apps legítimas lo usan (correo de empresa, localizador del móvil), pero el stalkerware lo usa para no poder ser eliminado.",
            "Mira cuál es en Ajustes › Seguridad › Administradores del dispositivo. Si no la reconoces, desactívala y desinstálala.",
            true
        ),
        ReglaInfo(
            "H7", "Dominio parecido a un indicador conocido",
            "Un dominio consultado comparte el nombre con un indicador conocido pero tiene otra terminación (por ejemplo .net en lugar de .com).",
            "Se consultó un dominio muy parecido al de una lista de indicadores.",
            "Los atacantes reutilizan nombres con terminaciones distintas para esquivar las listas.",
            "Búscalo con cuidado; puede ser una coincidencia inocente. Comprueba qué app lo consultó.",
            true
        ),
        ReglaInfo(
            "H8", "Consultas periódicas muy regulares",
            "Una app consulta el mismo dominio a intervalos casi idénticos, o envía un volumen muy alto de datos hacia una misma IP (este segundo caso solo con la captura de conexiones).",
            "Una app «llama a casa» con un ritmo de reloj.",
            "Las balizas de control remoto suelen ser muy regulares, pero también lo son las comprobaciones de conexión y las actualizaciones, por eso viene desactivada.",
            "Mira el dominio y la app. Los servicios conocidos (Google, Apple, tu operador) son normales.",
            false
        ),
        ReglaInfo(
            "H9", "Cambió la firma de una app instalada",
            "La huella de la firma de una app ya instalada es distinta a la de la foto anterior.",
            "Una app se sustituyó por otra con distinto firmante.",
            "Una app legítima conserva su firma en cada actualización; un cambio puede indicar una versión falsa.",
            "Comprueba de dónde viene la actualización. Si no la hiciste tú desde la tienda, desinstala la app y repórtala.",
            true
        ),
        ReglaInfo(
            "H10", "Proceso del sistema con dominios nuevos",
            "Procesos del sistema consultan dominios que no se habían visto antes.",
            "Algo del propio sistema contactó con un destino nuevo.",
            "Los procesos del sistema tienen un repertorio muy estable; un destino nuevo es poco habitual, aunque las actualizaciones lo explican a menudo.",
            "Mira los dominios. Si son de tu fabricante o de Google, suele ser normal.",
            false
        ),
        ReglaInfo(
            "H11", "DNS cifrado propio (DoH/DoT)",
            "Una app consulta un servidor de DNS cifrado (DNS sobre HTTPS o sobre TLS) por su cuenta.",
            "Una app puede estar resolviendo nombres sin pasar por el DNS del sistema.",
            "Así una app oculta sus consultas a Vigía y a cualquier filtro. Los navegadores lo hacen con frecuencia y es legítimo; el resto de apps es menos habitual.",
            "Si es tu navegador, probablemente es normal. Si es otra app, revisa para qué la usas; puedes bloquear ese servidor desde el Escudo.",
            true
        ),
        ReglaInfo(
            "MITM", "Red que altera certificados",
            "Comprobación bajo demanda (botón en el Escudo): compara los certificados de sitios conocidos por cada red.",
            "Una red presenta certificados que no validan, firmados por una CA de usuario o distintos de los de otra red.",
            "Una red con ese comportamiento puede estar leyendo o modificando el tráfico cifrado (wifi comprometida o intervenida).",
            "Evita usar esa red para nada sensible, usa datos móviles y revisa los certificados instalados en Ajustes › Seguridad.",
            true
        ),
        ReglaInfo(
            "NOTA", "Nota del análisis",
            "Avisos sobre lo que no se pudo evaluar.",
            "Una regla no pudo comprobarse con los datos de esta sesión.",
            "Sin los datos necesarios, esa regla no puede dar un resultado fiable (ni positivo ni negativo).",
            "Activa los módulos que falten en la próxima sesión.",
            true
        )
    )

    fun porId(id: String): ReglaInfo? = todas.firstOrNull { it.id == id }

    /** Reglas que el usuario puede activar o desactivar (todas menos las notas). */
    fun editables(): List<ReglaInfo> = todas.filter { it.id != "NOTA" && it.id != "MITM" }
}

/** Ajustes del análisis (reglas activas, parámetros, fuentes de indicadores). */
class AjustesAnalisis(contexto: Context) {
    private val prefs = contexto.applicationContext.getSharedPreferences("vigia_analisis", Context.MODE_PRIVATE)

    fun activa(regla: String): Boolean {
        val defecto = Reglas.porId(regla)?.defecto ?: true
        return prefs.getBoolean("regla_" + regla, defecto)
    }

    fun activar(regla: String, valor: Boolean) {
        prefs.edit().putBoolean("regla_" + regla, valor).apply()
    }

    fun entero(clave: String, defecto: Int): Int = prefs.getInt(clave, defecto)

    fun fijarEntero(clave: String, valor: Int) {
        prefs.edit().putInt(clave, valor).apply()
    }

    var usarIndiceMvt: Boolean
        get() = prefs.getBoolean("ioc_indice_mvt", true)
        set(valor) {
            prefs.edit().putBoolean("ioc_indice_mvt", valor).apply()
        }

    var actualizarAlIniciar: Boolean
        get() = prefs.getBoolean("ioc_auto", false)
        set(valor) {
            prefs.edit().putBoolean("ioc_auto", valor).apply()
        }

    var ultimaActualizacionIoc: Long
        get() = prefs.getLong("ioc_ultima", 0L)
        set(valor) {
            prefs.edit().putLong("ioc_ultima", valor).apply()
        }

    fun fuentesExtra(): List<String> {
        val texto = prefs.getString("ioc_fuentes", "") ?: ""
        return texto.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun fijarFuentesExtra(lista: List<String>) {
        prefs.edit().putString("ioc_fuentes", lista.joinToString("\n")).apply()
    }

    companion object {
        const val H1_INICIO = "h1_inicio"
        const val H1_FIN = "h1_fin"
        const val H8_MIN_CONSULTAS = "h8_min"
        const val H8_MB = "h8_mb"
        const val CORR_VENTANA_S = "corr_ventana"
        const val BASE_MIN_MUESTRAS = "base_min_muestras"
    }
}
