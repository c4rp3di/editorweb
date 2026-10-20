# SPI — AnalyzerModule

La interfaz que todo módulo descargable implementa. Vive en
`app/src/main/java/com/carpe/panoptes/modules/spi/AnalyzerModule.kt`,
duplicada a mano en cada proyecto de `modules-src/` (ver
`docs/ARCHITECTURE.md`, sección de stubs).

```kotlin
interface AnalyzerModule {
    fun id(): String
    fun version(): String
    fun initialize(context: Context, workDir: File): Boolean
    fun analyze(apkPath: String, outputDir: File, listener: ProgressListener): ModuleResult
    fun shutdown()

    interface ProgressListener {
        fun onProgress(phase: String, percent: Int)
        fun onLog(line: String)
    }

    data class ModuleResult(
        val success: Boolean,
        val jsonOutput: String,
        val outputFiles: List<String>,
        val error: String? = null
    )
}
```

## Regla de oro

Contrato estable. Los cambios se hacen SIEMPRE por adición (métodos
nuevos con valor por defecto), nunca por modificación o eliminación —
romper esto invalida todos los módulos ya publicados (sus DEX ya
compilados siguen esperando la firma antigua). Si dudas si añadir un
método, no lo añadas: los módulos pueden comunicarse por archivos en
`workDir` o por el propio `jsonOutput`.

## Limitación conocida, honesta

`analyze(apkPath, ...)` no recibe el `packageName` de la app analizada
explícitamente — casi ningún módulo lo necesita (trabajan sobre el
fichero APK), excepto `frida-server`, que necesita saber qué paquete
lanzar para instrumentarlo. `FridaController.kt` lo adivina ahora mismo a
partir del nombre de fichero que genera `ApkExtractor`
(`<packageName>-<version>-<original>.apk`), lo cual es frágil y está
marcado como tal en el propio código.

Arreglo correcto, pendiente: añadir `fun packageName(): String =
File(apkPath)...` no vale como default sensato porque el valor real debe
venir del llamador (`AnalysisOrchestrator` ya lo tiene, por
`AnalysisEntity.packageName`) — la vía compatible con la regla de oro es
añadir un parámetro opcional a `analyze()` con valor por defecto, p.ej.:

```kotlin
fun analyze(
    apkPath: String,
    outputDir: File,
    listener: ProgressListener,
    packageName: String? = null  // nuevo, con default -> no rompe módulos ya publicados
): ModuleResult
```

No se ha hecho en F7 porque tocar la SPI, aunque sea por adición, implica
recompilar y republicar los cuatro módulos existentes para que empiecen a
recibir el dato — se dejó anotado aquí para cuando toque esa ronda de
republicación en vez de colarlo como último cambio de la fase final.
