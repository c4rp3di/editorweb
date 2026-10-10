package es.metromadrid.probe

/** Estado local en memoria: no se persiste ni se transmite. */
object ProbeState {
    private var latest: String = "Aún no se ha leído ninguna pantalla de Metro.\n\n" +
        "1. Activa la sonda en Ajustes > Accesibilidad.\n" +
        "2. Abre Metro de Madrid.\n" +
        "3. Vuelve aquí y pulsa Actualizar lectura."
    private var capturedAt: Long = 0L

    @Synchronized fun store(text: String, at: Long) { latest = text; capturedAt = at }
    @Synchronized fun read(): String = latest
    @Synchronized fun time(): Long = capturedAt
    @Synchronized fun clear() { latest = "Lectura borrada de la memoria."; capturedAt = 0L }
}
