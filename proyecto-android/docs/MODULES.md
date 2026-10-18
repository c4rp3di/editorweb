# Módulos

## Catálogo

`module_catalog.json` (publicado como release `catalog-latest`, o el
embebido en `assets/` como fallback) lista los módulos disponibles. Cada
entrada: `id`, `version`, `size_bytes`, `sha256` (del ZIP),
`signature_sha256` (del DEX dentro del ZIP), `url`, `entry_class`,
`requires_root`, `requires_shim`. Ver `ModuleCatalog.kt` para el parseo
exacto y `scripts/generate-catalog.py` para cómo se regenera desde los
releases `deps-*`.

## Ciclo de vida de un módulo

1. `ModuleDownloader.downloadAndInstall()`: descarga a `cacheDir`,
   verifica SHA-256 del ZIP, descomprime a staging, verifica SHA-256 del
   DEX contra `signature_sha256`, mueve a `filesDir/modules/<id>/`
2. `ModuleManager.scanInstalled()`: relee `filesDir/modules/` y
   reconstruye el mapa de instalados a partir de cada `module.json`
3. `ModuleManager.load(id)`: `DexClassLoader` + reflexión, instancia la
   clase `entry_class`, llama a `initialize()`
4. `AnalysisOrchestrator` llama a `analyze()` si el módulo está instalado
   (automáticamente para jadx/apktool/radare2 dentro de `run()`; a
   propósito para frida-server vía `runDynamicAnalysis()`)
5. `ModuleManager.unload(id)` llama a `shutdown()` y libera la instancia

## Módulos disponibles

| id | tipo | requiere | qué hace |
|---|---|---|---|
| `jadx` | JVM puro | — | decompila DEX a Java legible |
| `apktool` | JVM puro | — | decodifica recursos/manifest completos |
| `radare2` | binario nativo | `libexec-shim` | análisis de `.so` (imports/exports) |
| `frida-server` | binario nativo | `libexec-shim` + root | instrumentación dinámica, SSL unpinning |

## Añadir un módulo nuevo

1. Copia el patrón de `modules-src/<nombre>-wrapper/`: `settings.gradle`,
   `build.gradle` (independiente, no entra en el `settings.gradle.kts`
   raíz), y duplica `AnalyzerModule.kt` (SPI) dentro de ese proyecto
2. Si necesita llamar a clases reales del shell (`ExecShim`,
   `PythonBridge`), añade el mismo patrón de stub `compileOnly` +
   exclusión del jar final que usan `radare2-wrapper`/`frida-wrapper` —
   ver el aviso `⚠️ CRÍTICO` en sus `build.gradle`
3. Implementa `AnalyzerModule` en tu clase `entry_class`
4. Escribe el workflow `.github/workflows/build-module-<nombre>.yml`
   (copia el de jadx o radare2 según si es JVM puro o binario nativo)
5. Añade la entrada a `MODULOS_CONOCIDOS` en `scripts/generate-catalog.py`
6. Corre el workflow, luego `publish-catalog.yml`
