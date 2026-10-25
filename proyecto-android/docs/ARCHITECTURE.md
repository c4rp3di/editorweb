# Arquitectura

Ver también el plan técnico original (el documento que arrancó este
repo) para el razonamiento detrás de las decisiones — esto es un resumen
de cómo quedó implementado al llegar a F7, no una repetición de ese plan.

## Capas

```
shell (:app, único módulo Gradle)
├── ui/            Compose (F3). Un NavHost, sin Activities por pantalla.
├── ingest/        ApkExtractor — listar/extraer APKs instalados (F0)
├── core/db/       Room: analysis, permission, component, endpoint,
│                  secret, native_lib, runtime_event, finding (+FTS4)
├── core/repo/     Repositorios finos sobre los DAOs
├── analyzer/
│   ├── dexlib/        DexParser (dexlib2) + StringScanner (regex)  — F1
│   └── orchestrator/  AnalysisOrchestrator, ManifestParser          — F1-F6
├── modules/       ModuleManager, ModuleDownloader, ModuleCatalog(Repository) — F2
├── nativebridge/  ExecShim (JNI, libexec-shim.so)                   — F4
├── python/        PythonBridge (Chaquopy)                            — F5
├── dynamic/       RootChecker                                        — F6
├── report/        ReportGenerator (HTML/JSON/SARIF)                  — F7
└── work/          AnalysisWorker (WorkManager + foreground notif.)

modules-src/ (builds Gradle independientes, NO en settings.gradle.kts)
├── jadx-wrapper/      decompilador Java — JVM puro
├── apktool-wrapper/   recursos/manifest — JVM puro
├── radare2-wrapper/   binarios nativos — proceso vía ExecShim
└── frida-wrapper/     dinámico con root — proceso vía ExecShim + Chaquopy
```

## Por qué el shell es un único módulo Gradle

`settings.gradle.kts` solo incluye `:app`. Los módulos de `modules-src/`
se compilan aparte, en CI, y se combinan con sus dependencias (jadx-core,
apktool-lib, el binario de radare2/frida-server) en un único
`classes.dex` con `d8`. Esto es lo que permite que el shell siga siendo
una build simple y rápida sin importar cuántos módulos tenga el catálogo.

## El patrón de los "stubs compileOnly"

`AnalyzerModule.kt` (la interfaz SPI) se duplica a mano en cada proyecto
de `modules-src/` porque son builds independientes sin visibilidad sobre
las fuentes de `:app`. Para los módulos que además necesitan llamar a
clases reales del shell en tiempo de ejecución (`ExecShim`, `PythonBridge`
— ver radare2-wrapper y frida-wrapper), se usa el mismo truco: un stub
`compileOnly` que nunca se empaqueta en el DEX final del módulo (se
excluye explícitamente en el `build.gradle` de cada wrapper). En
runtime, `DexClassLoader` delega primero al classloader padre (el de la
app) para cualquier clase, así que la referencia se resuelve a la
implementación real del shell, no al stub.

Si tocas `ExecShim` o `PythonBridge`, cambia la firma en el shell Y en
cada stub — no hay forma automática de mantenerlos sincronizados ahora
mismo (ver "Mejoras pendientes" más abajo).

## Flujo de un análisis

1. `MainActivity` → `AppsScreen` → se toca una app → `ApkExtractor.extraer()`
2. Se registra un `AnalysisEntity` (estado `PENDIENTE`) y se encola
   `AnalysisWorker` (WorkManager, notificación en primer plano)
3. `AnalysisWorker` → `AnalysisOrchestrator.run()`:
   DEX (dexlib2) → strings (regex) → manifest (PackageManager) → Python
   (androguard siempre, quark si hay reglas) → módulos instalados
   (jadx/apktool/radare2) → libs nativas → correlación mínima → Findings
4. El análisis dinámico (frida-server) es la excepción: no corre en el
   Worker, se dispara a mano desde `AnalysisDetailScreen`
   (`AnalysisOrchestrator.runDynamicAnalysis()`)
5. Desde `AnalysisDetailScreen` se puede exportar todo a HTML/JSON/SARIF
   (`ReportGenerator`, F7) y compartirlo por el share sheet del sistema

## Mejoras pendientes conocidas (honestas, no una lista de deseos vaga)

- `FridaController.inferirPaqueteDesdeApkPath` adivina el package name
  del nombre de fichero — frágil. Arreglo correcto: añadir el
  `packageName` como parámetro explícito de `AnalyzerModule.analyze()`
  (por adición, ver `docs/SPI.md`).
- No hay purga automática de `filesDir/analysis/<id>/` — crece sin límite.
- El análisis dinámico corre en el ViewModel, no en un Worker con
  `setForeground` — se puede interrumpir si la app pasa a segundo plano.
- Sin tests automatizados en ningún módulo (ver `docs/BUILD.md`).
