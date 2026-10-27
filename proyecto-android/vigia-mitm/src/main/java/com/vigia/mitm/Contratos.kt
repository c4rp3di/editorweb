package com.vigia.mitm

/** Qué tipo de recurso pide una petición HTTP (según Sec-Fetch-Dest, la extensión o Accept). */
enum class TipoRecurso { SCRIPT, IMAGEN, ESTILO, DOCUMENTO, OTRO }

/** Lo que el MitM necesita saber del filtro. Lo implementa la app: el módulo no conoce listas ni preferencias. */
interface FiltroContenido {
    /** Verdadero si el recurso debe sustituirse por un 200 OK vacío. [host] llega en minúsculas, sin puerto. */
    fun bloquear(host: String, ruta: String, tipo: TipoRecurso): Boolean

    /** Reglas CSS (sin etiquetas <style>) para ocultar huecos de anuncios en [host], o null/vacío si ninguna. */
    fun cssCosmetico(host: String): String?
}

/** Decisiones de intermediación y lista blanca automática de SSL pinning. Lo implementa la app. */
interface PoliticaMitm {
    /**
     * ¿Se intercepta esta conexión? Se consulta al redirigir, antes de ningún byte TLS, así que solo se conoce la app
     * ([app] == «desconocida» en Android < 10) y la IP de destino. Falso = conexión directa al servidor real.
     */
    fun intermediar(app: String, ip: String): Boolean

    /**
     * El cliente rechazó nuestro certificado (SSL pinning, o la app no confía en CAs de usuario). La app debe
     * recordar que no se vuelva a intermediar y la siguiente conexión (el reintento) irá directa al servidor real.
     */
    fun alFallarHandshake(app: String, ip: String, host: String, motivo: String)
}

/** Eventos para estadísticas / registro. Se llaman desde hilos del proxy: deben ser rápidos. */
interface EventosMitm {
    fun alBloquear(host: String, ruta: String, tipo: TipoRecurso) {}
    fun alInyectarCss(host: String) {}
    fun alFallbackPinning(app: String, host: String, motivo: String) {}
    fun alError(host: String, mensaje: String) {}
}
