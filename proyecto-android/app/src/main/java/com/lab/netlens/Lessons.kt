package com.lab.netlens

object Lessons {
    val ALL: List<Pair<String, String>> = listOf(
        "1 · ¿Qué hace NetLens?" to
            "Crea una VPN LOCAL: Android entrega a NetLens todos los paquetes que las apps quieren enviar. NetLens los lee, los reenvía a Internet por su cuenta y te muestra el resumen. No hay servidor intermedio ni se sube nada: todo ocurre en este móvil y se borra al cerrar la app.",
        "2 · Qué sí se ve sin descifrar" to
            "• DNS: qué nombres pregunta cada app (api.ejemplo.com) y a qué IP apunta.\n• Destino: IP y puerto de cada conexión.\n• SNI de TLS: el nombre del servidor viaja en claro en el saludo inicial (ClientHello).\n• Tamaño y momento: cuánto sube y baja, y cuándo.\n• HTTP sin cifrar: método, host, ruta y NOMBRES de parámetros (nunca guardamos los valores).\n• Quién: Android permite saber qué app (UID) abrió cada conexión.",
        "3 · Qué NO se ve sin MITM" to
            "El contenido de HTTPS/TLS va cifrado de extremo a extremo entre la app y el servidor. Un observador de red (esto mismo, tu operador, el Wi-Fi) ve a dónde y cuánto, no qué. Por eso 'contacta con un dominio de analítica' no prueba que envíe tu ubicación: es una pista, no una prueba.",
        "4 · QUIC / HTTP3" to
            "Muchas apps de Google, Meta, etc. usan QUIC (UDP 443), que cifra incluso el saludo. Activa «Bloquear QUIC»: las apps vuelven a TCP+TLS y podrás ver el SNI. Es una prueba de laboratorio, no algo que dejar activado siempre.",
        "5 · ECH y DNS privado" to
            "ECH (Encrypted Client Hello) esconde el SNI; DNS-over-HTTPS/TLS esconde las consultas DNS. Si una app los usa verás menos nombres. Con «DNS privado» activado en Ajustes, las consultas del sistema pueden saltarse la vista DNS de NetLens.",
        "6 · MITM TLS (fase 2, solo para TUS apps)" to
            "NetLens puede generar una CA propia, firmar certificados al vuelo y terminar el TLS entre la app y el servidor. Para que una app acepte la CA: exporta la CA desde el botón «Exportar CA», instálala en Ajustes → Seguridad → Cifrado y credenciales → Instalar certificado → CA, y activa «MITM TLS» en NetLens.\n\nLa app debe confiar en CAs de usuario. Desde Android 7, solo lo hacen si su network_security_config.xml incluye:\n\n<debug-overrides>\n  <trust-anchors>\n    <certificates src=\"user\"/>\n  </trust-anchors>\n</debug-overrides>\n\nEn apps de terceros con certificate pinning (bancos, redes sociales…) el handshake fallará. Ese es el comportamiento correcto: NO se debe saltar el pinning. El MITM es para tus apps de laboratorio, no para auditar apps ajenas.",
        "7 · Permisos: qué puede y qué no esta app" to
            "NetLens pide: VPN (consentimiento del sistema, no se puede saltar), notificaciones (para el aviso permanente) y ver la lista de apps instaladas. NO puede concederse permisos a sí misma ni a otras apps en silencio: Android lo impide a propósito. Desde la pestaña Permisos abrimos los ajustes de cada app para que seas tú quien conceda o retire.",
        "8 · Úsalo con ética" to
            "Observa tu propio móvil y tus propias apps. Inspeccionar el tráfico de otras personas o de redes ajenas sin permiso es ilegal en la mayoría de países. NetLens es una herramienta para entender y reducir el alcance de lo que programas: ¿cuántos dominios contacta tu app?, ¿envía algo en claro?, ¿pide permisos que no usa?"
    )
}

