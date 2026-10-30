package com.vigia.adblock

/** Guía del Laboratorio (adaptada de las «lecciones» de NetLens). */
object Lecciones {
    val TODAS: List<Pair<String, String>> = listOf(
        "1 · Qué hace el Laboratorio" to
            "AdBlock Soberano Lab ya crea una VPN LOCAL: Android le entrega los paquetes que las apps quieren enviar. El Laboratorio " +
            "solo anota, en la memoria del móvil, quién hace qué: app, nombre consultado, destino y bytes. No hay servidor " +
            "intermedio ni se sube nada, y todo se borra al cerrarse la app.",
        "2 · Qué sí se ve sin descifrar" to
            "• DNS: qué nombres pregunta cada app y qué responde.\n" +
            "• Destino: IP y puerto de cada conexión.\n" +
            "• SNI de TLS (solo en Modo Profundo): el nombre del servidor viaja en claro en el saludo inicial.\n" +
            "• Tamaño y momento: cuánto sube y baja, y cuándo.\n" +
            "• Puerto 80: conexiones HTTP sin cifrar (el contenido no se mira).\n" +
            "• Quién: Android 10 o superior permite saber qué app abrió cada conexión; en versiones anteriores sale «desconocida».",
        "3 · Qué NO se ve sin descifrar" to
            "El contenido de HTTPS va cifrado de extremo a extremo entre la app y el servidor. Un observador de red (esto mismo, tu " +
            "operador, el Wi-Fi) ve a dónde y cuánto, no qué. Que una app contacte con un dominio de analítica no prueba que envíe " +
            "tu ubicación: es una pista, no una prueba.",
        "4 · Ligero o Profundo" to
            "Ligero solo ve las consultas DNS: es barato y suficiente para saber a qué dominios pregunta cada app. Profundo ve además " +
            "cada conexión (destino, SNI, bytes) y bloquea QUIC para que las apps vuelvan a TCP+TLS, donde el nombre es visible. " +
            "Para investigar una app, enciende Profundo y el registro; cuando acabes, vuelve a Ligero.",
        "5 · ECH, DoH y IPv6" to
            "ECH esconde el nombre del servidor; DoH/DoT esconden las consultas DNS; IPv6 puede saltarse la VPN. Las tres opciones de " +
            "«Anti-evasión» cierran esos atajos para que lo que ves (y lo que bloqueas) esté completo. Si una app deja de funcionar " +
            "tras activarlas, desactiva primero «Neutralizar IPv6».",
        "6 · Descifrado HTTPS (MitM)" to
            "La app puede generar una CA propia, firmar certificados al vuelo y terminar el TLS entre la app y el servidor, para vaciar " +
            "anuncios por ruta. Exporta la CA en Ajustes → Descifrado HTTPS, instálala en Ajustes de Android → Seguridad → Cifrado y " +
            "credenciales → Instalar un certificado → Certificado de CA, y activa el interruptor.\n\n" +
            "Desde Android 7 casi ninguna app acepta CAs de usuario (los navegadores sí). Las que rechazan el certificado, por " +
            "pinning o por no confiar en él, se dejan sin descifrar solas. El pinning NO se salta: es el comportamiento correcto.",
        "7 · Permisos" to
            "La app pide: VPN (consentimiento del sistema, no se puede saltar), notificaciones (para el aviso permanente) y nada más. " +
            "No puede conceder ni retirar permisos a otras apps: Android lo impide a propósito. Desde el detalle de cada app abrimos " +
            "sus ajustes para que seas tú quien lo haga. Solo se ven las apps con icono de lanzador.",
        "8 · Úsalo con ética" to
            "Observa tu propio móvil y tus propias apps. Inspeccionar el tráfico de otras personas o de redes ajenas sin permiso es " +
            "ilegal en la mayoría de países. La idea es entender y reducir el alcance de lo que instalas: ¿a cuántos dominios se " +
            "conecta?, ¿envía algo en claro?, ¿pide permisos que no usa?"
    )
}
