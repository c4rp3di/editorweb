package com.luzcontrol.app.domain.model

/** Errores de dominio traducibles a mensajes legibles para el usuario. */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object NoCredentials : AppError("No hay credenciales de Datadis configuradas")
    object InvalidCredentials : AppError("Credenciales incorrectas o caducadas")
    data class RateLimited(val retryAfterSeconds: Long? = null) : AppError("Demasiadas solicitudes")
    class NoNetwork(cause: Throwable? = null) : AppError("Sin conexión o red muy lenta", cause)
    class Server(val code: Int, msg: String? = null) : AppError("Error del servidor ($code) ${msg.orEmpty()}")
    class Parse(cause: Throwable? = null) : AppError("Respuesta inesperada del servidor", cause)
    class Unknown(cause: Throwable? = null) : AppError(cause?.message ?: "Error desconocido", cause)

    fun userMessage(): String = when (this) {
        NoCredentials -> "Configura tus credenciales de Datadis en Ajustes para ver tu consumo."
        InvalidCredentials -> "Credenciales incorrectas o caducadas. Revísalas en Ajustes."
        is RateLimited -> "Demasiadas solicitudes a Datadis. La app esperará unos minutos antes de reintentar."
        is NoNetwork -> "Sin conexión o la red es muy lenta. Se usarán los datos guardados."
        is Server -> "No se pudo conectar con el servicio (código $code). Se reintentará automáticamente."
        is Parse -> "El servicio devolvió una respuesta inesperada. Se reintentará automáticamente."
        is Unknown -> "Ha ocurrido un error inesperado. Se reintentará automáticamente."
    }
}
