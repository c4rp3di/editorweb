package es.metromadrid.probe

/** Estado solo en memoria: no se escribe en disco ni se transmite por red. */
object ProbeState {
    private var latest: String = "Todavía no se ha capturado ninguna pantalla de Metro.\n\n" +
        "1. Activa la sonda en Ajustes > Accesibilidad.\n" +
        "2. Abre Metro de Madrid y entra en la pantalla de trenes/carrusel.\n" +
        "3. Vuelve a esta sonda y pulsa Actualizar lectura."
    private var capturedAt: Long = 0L

    @Synchronized fun store(text: String, at: Long) {
        latest = text
        capturedAt = at
    }

    @Synchronized fun read(): String = latest
    @Synchronized fun time(): Long = capturedAt

    @Synchronized fun clear() {
        latest = "Captura borrada de la memoria de la sonda."
        capturedAt = 0L
    }
}
