# APK Panoptes — v1.0 (F0 a F7 completas)

**⚠️ v1.0.1 — primera prueba real de importación (post-F7):** al importar
este proyecto en el editor web de verdad, `settings.gradle.kts`/
`build.gradle.kts`/`app/build.gradle.kts` (de F3) volvieron a Groovy
porque el editor no compila con Kotlin DSL — ver la nota dentro de la
sección F3 más abajo para el porqué exacto. Además 36 archivos no se
importaron la primera vez (todo `modules-src/`, `docs/`, `scripts/`,
`README.md`, y los `.py`/`.c`/`jniLibs` de `app/src/main/`) por huecos del
*importador* del editor — esos huecos ya están arreglados en el editor
(ver conversación), pero si usas una copia vieja del HTML del editor,
sigue sin reconocerlos.

**F7 añadido (última fase del plan):** `ReportGenerator.kt` exporta
cualquier análisis a **HTML** (informe legible, con disclaimer incluido),
**JSON** (datos completos, secretos sin enmascarar — es tu export sobre tu
propio APK) y **SARIF 2.1.0** (formato estándar, compatible con GitHub
Code Scanning / VS Code si algún día quieres cruzarlo con otras
herramientas). Desde `AnalysisDetailScreen` hay un menú de exportar que
genera el fichero y abre el share sheet del sistema directamente. También
rellené los seis `docs/*.md` que el plan listaba en su estructura de
repo (§2.2) desde F0 pero habían quedado vacíos hasta ahora:
`ARCHITECTURE.md`, `BUILD.md`, `MODULES.md`, `SPI.md`, `ANALYSIS.md`,
`LEGAL.md`.

**Lo que quedó fuera de F7 a propósito**, por ir ordenado de "decisión de
alcance razonable" a "de verdad no me dio tiempo/no pude verificarlo":

- **PDF** no está, solo HTML/JSON/SARIF. El HTML generado es
  autocontenido (CSS inline) — "imprimir a PDF" desde cualquier visor
  cubre el caso de uso sin añadir una librería de generación de PDF más.
- **Widget de pantalla de inicio**: lo menciona el roadmap (mes 10) pero
  no la columna de "entregable" de la fase F7 en la tabla de fases — lo
  dejé fuera para no inflar el alcance del cierre con algo que el plan
  mismo trata como opcional.
- **Pruebas en 5+ dispositivos físicos y corpus público de validación**
  (meses 11-12 del roadmap): esto, por definición, no lo puedo hacer yo
  — necesita hardware real y es el paso que de verdad te toca a ti antes
  de llamar a esto "v1.0 estable" de verdad.
- **Purga automática de `filesDir/analysis/<id>/`**: nunca se implementó
  en ninguna fase: crece sin límite. Está anotado en `docs/LEGAL.md` y
  `docs/ARCHITECTURE.md` como mejora pendiente conocida, no escondido.

## Estado real del proyecto, con honestidad, fase por fase

No he podido compilar ni ejecutar nada de este código en ningún momento
de las 7 fases — sin red ni Android SDK/NDK en este entorno. Así que esto
no es "v1.0 estable", es **v1.0 del código fuente completo del plan,
nunca compilado**. Antes de confiar en ello:

1. Ábrelo en Android Studio (es literalmente lo que pedía la fila F3 del
   plan) y corre un Gradle sync — ahí saldrán los primeros errores reales
   (probablemente nombres de función de alguna API de Compose/Room/
   dexlib2 que cambió de versión).
2. De más a menos fiable, por mi propia confianza en cada pieza:
   `ingest`/`core`/UI Compose (F0-F3) > `modules`/`ModuleDownloader` (F2)
   > `ManifestParser`/`StringScanner` (F1) > `jadx`/`apktool` wrappers
   (F2/F4) > `libexec-shim` (F4) > `androguard_runner.py` (F5) >
   `radare2` (binario Android incierto, F4) > `ReportGenerator` (F7, no
   verificado contra el schema SARIF real) > `quark_runner.py` (F5) >
   **`frida_runner.py`/control on-device de Frida (F6), con diferencia lo
   menos fiable de todo**.
3. Cada fase tiene su propia sección más abajo en este mismo README con
   los avisos `⚠️` específicos — no los borré al avanzar de fase, así que
   este documento es largo a propósito: es el registro real de qué se
   construyó, qué se verificó y qué no, en cada paso.

---

# APK Panoptes — F0 a F5 + F6

**F6 añadido:** módulo `frida-server` (único que requiere root), script
genérico de SSL unpinning + registro de URLs (`assets/frida-scripts/
ssl-unpinning-and-log.js` — técnica pública y estándar, la misma que
documenta OWASP MASTG), `RootChecker.kt`, correlación estático-dinámica
(`ResultsDao.marcarEndpointVistoEnRuntime`), y un botón explícito de
"análisis dinámico" en el detalle (nunca automático: lanza la app
analizada y tarda ~15s).

## El aviso más importante de todo el proyecto hasta ahora

**La pieza de control on-device de Frida (`python/frida_runner.py`) es
más incierta que todo lo anterior junto, quark-engine incluido.** El
paquete `frida` de PyPI envuelve una extensión nativa compilada; que
Chaquopy tenga un wheel de eso para Android/arm64 es poco probable — no
es un paquete pensado para ejecutarse *dentro* de un dispositivo Android,
sino para controlar frida-server *desde un PC* por USB o red. Por eso:

- `FridaController.kt` **siempre** arranca frida-server correctamente si
  hay root (esa parte no depende de nada incierto).
- Si el control on-device falla (lo más probable), el módulo lo refleja
  como éxito parcial (`"onDeviceControl": false`) y frida-server se queda
  escuchando en `127.0.0.1:27042` — conéctate desde tu PC con
  `adb forward tcp:27042 tcp:27042` y luego `frida -H 127.0.0.1:27042
  -f <paquete> -l ssl-unpinning-and-log.js`, que es como se usa Frida
  normalmente. El pipeline on-device es el intento optimista, no la única
  vía.

Otros puntos de F6:

- El botón de análisis dinámico corre en el `ViewModel` (no en
  `WorkManager`) — si la app pasa a segundo plano durante los ~15s se
  puede interrumpir. Para una versión robusta habría que moverlo a un
  Worker con `setForeground`, como `AnalysisWorker` — lo dejé fuera a
  propósito para no repetir ~40 líneas de ceremonia de notificación por
  algo que todavía es experimental.
- `inferirPaqueteDesdeApkPath` en `FridaController.kt` adivina el nombre
  de paquete a partir del nombre de fichero que genera `ApkExtractor` —
  es frágil y lo digo en el propio código; arreglarlo bien implica tocar
  la interfaz SPI para pasar el `packageName` explícitamente (por
  adición, ver Apéndice B del plan).
- `RootChecker` es deliberadamente simple: no intenta evadir
  ocultadores de root. Eso sería un problema distinto al que F6 pide
  resolver.

---

# APK Panoptes — F0 + F1 + F2 + F3 + F4 + F5

**F5 añadido:** Chaquopy (Python embebido) + `panoptes/androguard_runner.py`
(firma v1/v2/v3, permisos declarados vs. solicitados) + `panoptes/
quark_runner.py` (scoring de comportamiento). El orquestador los llama
siempre como fase [4], antes de los módulos.

## Avisos grandes de esta fase — leer antes de compilar

1. **Licencia de Chaquopy.** No he podido verificar en qué términos está
   hoy (puede cambiar). Revisa chaquo.com/chaquopy/ antes de distribuir
   nada más allá de uso personal — el propio plan ya acepta el coste de
   tamaño (+35MB) pero no entra en licencia, y yo tampoco puedo
   confirmártela sin red.
2. **quark-engine es la pieza menos fiable de todo el proyecto hasta
   ahora.** `quark_runner.py` lo dice en su propia cabecera: no es una
   integración verificada, es un esqueleto. Dos problemas reales: (a) su
   API Python cambia bastante entre versiones y no pude confirmar que
   coincide con `23.9.1` exacto; (b) quark-engine no trae las reglas
   embebidas — normalmente se descargan aparte con su herramienta
   `freshquark`, lo cual no encaja con "sin telemetría" del plan. Por eso
   `PythonBridge.kt` busca un `assets/quark-rules.zip` opcional (que yo
   **no he incluido** — tendrías que empaquetar tú un subconjunto de
   github.com/quark-engine/quark-rules) y si no lo encuentra, quark se
   salta con gracia (`"skipped": true`) en vez de fallar.
3. **Disponibilidad de wheels.** `androguard==4.1.2` y
   `quark-engine==23.9.1` (y sus propias dependencias transitivas) tienen
   que existir como wheels precompilados en el repo de Chaquopy, no en
   PyPI genérico — es justo el riesgo que el plan ya apuntaba en su
   matriz (§10: "Chaquopy no encuentra algún wheel"). La primera build con
   `python { pip { ... } }` es el momento de la verdad; si algo no
   resuelve, prueba fijando una versión distinta antes de asumir que el
   enfoque está mal.
4. `androguard_runner.py` es la parte sólida de esta fase — usa
   `androguard.core.apk.APK` (no `AnalyzeAPK`, que haría un análisis DEX
   completo y duplicaría lo que ya hace dexlib2 en F1) para info de
   firma/certificado que `PackageManager` no da fácilmente. Esa es la que
   más confío en que funcione tal cual está escrita.

---

# APK Panoptes — F0 + F1 + F2 + F3 + F4

**F4 añadido:** `libexec-shim` nativo (§4 del plan) + dos módulos nuevos:
**apktool** (decodifica recursos/manifest completos, JVM puro) y
**radare2** (análisis de binarios nativos — el único módulo que no es una
librería JVM, se ejecuta como proceso vía el shim). El orquestador ahora
lista las `.so` del APK siempre (sin módulo) y las analiza con radare2 si
está instalado.

## Lo que más atención necesita de toda la build (por orden de riesgo)

1. **El binario de radare2 para Android arm64.** No he podido verificar
   si radare2 publica un asset precompilado para Android en todas sus
   releases de GitHub — varía según versión. `build-module-radare2.yml`
   trae dos caminos marcados `⚠️ CAMINO A` / `⚠️ CAMINO B` (descarga vs.
   compilar desde fuente con meson+NDK, que es el método que documenta el
   propio proyecto radare2) — el B está deliberadamente sin terminar
   (`TODO`), revísalo antes de lanzar ese workflow.
2. **El truco de `ExecShim` duplicado-pero-excluido** en
   `modules-src/radare2-wrapper`: el stub compileOnly nunca debe acabar en
   el `classes.dex` del módulo (lo excluye `build.gradle` del wrapper con
   un comentario `⚠️ CRÍTICO` explicando por qué). Si alguna vez tocas ese
   pipeline, no te olvides de esa exclusión o el módulo falla en runtime.
3. **`libexec-shim` en ROMs no-AOSP** (Xiaomi/Huawei/etc.) — es el riesgo
   que el propio plan ya marcaba en su matriz de riesgos (§10): "probar en
   dispositivos físicos" antes de confiar en que funciona en todas partes.
   No tengo forma de validarlo sin hardware real.
4. **`build-shell-apk.yml` es nuevo en F4** porque a partir de aquí el
   shell necesita NDK si no hay `libexec-shim.so` ya publicado — dispara
   primero `build-shim.yml`, luego este. Necesita los secrets de firma
   (`SIGNING_KEY`, `KEY_ALIAS`, etc.) configurados en el repo.
5. El asset de apktool en GitHub Releases: dejé un aviso `⚠️` en
   `build-module-apktool.yml` porque suele publicarse como un único
   fat-jar (`apktool_X.Y.Z.jar`) en vez de un `.zip` con `lib/*.jar`
   sueltos como jadx — revísalo antes del primer run.

---

# APK Panoptes — F0 + F1 + F2 + F3

**F3 añadido (los dos cambios que pedía la Fase 3 del plan):**

1. **Groovy → Kotlin DSL**: `settings.gradle`/`build.gradle` →
   `.gradle.kts` en los tres sitios (raíz, `:app`). `modules-src/
   jadx-wrapper` se queda en Groovy a propósito — es un build
   independiente que solo toca la CI, no tiene por qué seguir el mismo
   ritmo de migración que el shell.

   **⚠️ REVERTIDO más tarde** (nota añadida después de F7, al probar la
   importación real en el editor web): el editor de compilación que de
   verdad se usa para este proyecto solo entiende Gradle Groovy
   (`.gradle`), no Kotlin DSL — importarlo generó el aviso "la
   compilación del editor espera Gradle Groovy" y además el propio
   importador de ZIP del editor descartaba `build.gradle.kts`/
   `settings.gradle.kts` enteros como "archivo de raíz que el editor no
   gestiona" (esto último ya está arreglado en el editor, pero el primer
   problema — que el *compilador* del editor sigue sin generar/usar
   `.kts` — no tiene arreglo razonable sin reescribir esa parte del
   editor). `settings.gradle`, `build.gradle` y `app/build.gradle`
   volvieron a Groovy. El resto de F3 (Compose, el diálogo legal) se
   queda igual.
2. **UI → Jetpack Compose**: se eliminaron todas las Activities/XML/
   Adapters de Views (F0-F2). Ahora es una sola `MainActivity`
   (`ComponentActivity`) con Navigation Compose; las antiguas pantallas
   son rutas (`ui/nav/Routes.kt`, `ui/nav/PanoptesNavHost.kt`):
   `AppsScreen`, `AnalysisListScreen`, `AnalysisDetailScreen`,
   `ModulesScreen`. Sin Hilt/Dagger (ver comentario en
   `ui/ViewModelFactory.kt`) — a este tamaño de app añadir DI es
   complejidad sin beneficio real.

**Extra incluido en F3, no estrictamente pedido por el plan pero que
encajaba aquí:** el diálogo de primer uso del §11 (`ui/settings/
LegalDialog.kt` + `SettingsRepository` con DataStore) que el plan pedía
"añadir al arrancar de la app" — tenía sentido hacerlo ahora que hay una
Activity raíz Compose limpia donde engancharlo, en vez de esperar a F7.

También recuperé en `AnalysisDetailScreen` la función de compartir/guardar
el APK extraído que tenía el extractor original de F0 y que se había
quedado fuera en F1/F2 (icono de compartir en la barra superior).

## Qué falta para que esto compile en Android Studio de verdad

- No he podido abrir esto en un IDE real ni compilarlo aquí (sin red, sin
  Android SDK). La superficie de Compose que usé (Material3 1.x vía BOM
  2024.09.00, Navigation Compose 2.8.1) es la API pública documentada de
  esas versiones, pero revísalo tú al primer build — es el sitio más
  probable para typos de nombres de función/parámetro.
- Sigue pendiente `Config.kt` con tu repo real (ver nota de F2 más abajo).
- A partir de aquí es buen momento para pasarte de verdad a Android
  Studio (gradle sync, autocompletado, preview de Compose) en vez de ir
  pegando archivos sueltos en el editor web — es literalmente lo que dice
  la fila F3 de la tabla de fases del plan.

---

# APK Panoptes — F0 + F1 + F2

**F2 añadido:** el sistema de módulos ya es real — `ModuleDownloader`
(descarga + verificación SHA-256 del ZIP y del DEX + protección zip-slip +
lista blanca de hosts), `ModuleCatalog`/`ModuleCatalogRepository` (catálogo
remoto publicado en GitHub Releases, con fallback al embebido), una
pantalla de gestión de módulos (`ModulesActivity`), y el primer módulo de
verdad: **jadx**, como proyecto Gradle independiente en `modules-src/
jadx-wrapper/` que implementa el SPI, más los workflows de CI que lo
compilan y publican (`build-module-jadx.yml`, `publish-catalog.yml`,
`scripts/generate-catalog.py`). El orquestador de F1 ya invoca el módulo
jadx si está instalado.

## Antes de compilar/usar esto
1. **Introduce tu repo en la app** (no hay que editar código):
   Ajustes → Catálogo de módulos: usuario/org y repositorio de GitHub.
   Sin esto el catálogo remoto no se usa (cae al embebido, vacío hasta que publiques algo).
   Sin esto el catálogo remoto nunca resuelve (cae al embebido, que sigue
   vacío hasta que publiques algo).
2. **El catálogo empieza vacío.** `ModulesActivity` no mostrará "jadx" como
   instalable hasta que ejecutes `build-module-jadx.yml` en tu repo (Actions
   → Build module jadx → Run workflow) y después `publish-catalog.yml`
   (se dispara solo al terminar el anterior, o a mano). Verifica primero el
   formato exacto del tag de releases de jadx — hay un comentario `⚠️` en el
   workflow donde mirar.
3. **No he podido compilar ni ejecutar nada de esto aquí** (sin red, sin
   Android SDK/NDK en este entorno) — ni el `d8` del empaquetado del módulo,
   ni la API real de `jadx-api` (la escribí contra la superficie pública
   documentada de jadx, pero si la versión que descargues difiere, el único
   archivo a tocar es `JadxAnalyzer.kt`).
4. Simplificación deliberada: usé `org.json` (ya en el SDK de Android) en
   vez de Moshi para parsear el catálogo, para no añadir otra dependencia
   más a lo que ya es bastante superficie nueva en F2.

## Cómo probarlo de principio a fin

1. Compila e instala el shell (igual que F0/F1).
2. Menú → **Módulos**. Si `Config.kt` apunta a un repo sin `catalog-latest`
   todavía, verás la lista vacía — es el comportamiento esperado, no un bug.
3. Dispara `build-module-jadx.yml` → espera a `publish-catalog.yml` → abre
   la app otra vez → "jadx" debería aparecer instalable.
4. Instálalo, extrae un APK desde la pantalla principal: el `AnalysisWorker`
   ahora también llama a jadx y añade un Finding `module:jadx` a los
   resultados si tuvo éxito.

## Qué sigue

F3: migración de la UI a Jetpack Compose y de Groovy a Kotlin DSL (ahí sí,
ya con Android Studio en vez del editor web). F4: `libexec-shim` + módulo
`apktool` + módulo `radare2`.

---

# APK Panoptes — F0 + F1

**F1 añadido:** parsing DEX con dexlib2, extracción de strings con regex
(URLs candidatas a endpoint, secretos candidatos por patrón + entropía),
manifest básico vía `PackageManager.getPackageArchiveInfo`, el resto del
esquema Room (§7) con FTS4, un `AnalysisWorker` (WorkManager + notificación
en primer plano) que corre el pipeline tras cada extracción, y dos
pantallas nuevas: lista de análisis y detalle de resultados.

Importante sobre los "secretos candidatos": son heurísticas (patrón +
entropía de Shannon), no confirmaciones. La pantalla de detalle enmascara
el valor en pantalla (`AbCd…wXyZ`) aunque se guarda completo en la base de
datos local del propio dispositivo, para que el dueño del APK pueda
revisarlo — igual que hacen herramientas como `gitleaks` o `truffleHog`
sobre tu propio código.

## Qué sigue pendiente de F1 real (§6.1 del plan)

- Fase [4] Python/Chaquopy (androguard, quark-engine) → F5.
- Fase [5] Nativo (radare2/Capstone) → F4.
- Fase [6] Dinámico (frida-server) → F6.
- Reporte HTML/JSON/SARIF → F7 (hoy los resultados solo se ven en la app).
- La correlación de F1 aquí es mínima (unas pocas reglas); el "grafo" de
  correlación completo del §6.1 es más ambicioso y puede esperar.

---

# APK Panoptes — F0 (shell inicial)

Generado a partir de "APK Panoptes — Plan Técnico v2" y del extractor
original (`com.carpe.extractor`). Corresponde al Apéndice A del plan:
el primer sprint, compilable y funcional.

## Qué incluye (F0)

- `:app` — único módulo Gradle, `com.carpe.panoptes`, minSdk 26.
- `ingest/ApkExtractor.kt` — tu extractor original, refactorizado fuera de
  la Activity (lista apps, extrae APK+splits a cache, genera Uris de
  FileProvider, calcula SHA-256).
- `core/db/` — Room con la entidad `AnalysisEntity` (registro de cada
  extracción). El resto del esquema del §7 (permisos, componentes,
  endpoints, secretos...) se añade en F1, junto con el parsing DEX real.
- `modules/spi/AnalyzerModule.kt` — la interfaz SPI completa (Apéndice B),
  ya estable para que los módulos de F2 la implementen.
- `modules/ModuleManager.kt` — escaneo y carga de módulos vía
  `DexClassLoader`. **No** incluye `ModuleDownloader`/`ModuleVerifier`
  (descarga desde GitHub Releases, verificación SHA-256+firma) — eso es F2,
  a propósito no está aquí para no sugerir que ya se puede descargar nada.
- UI mínima con Views (RecyclerView), igual que el extractor actual.
  Compose llega en F3, como dice el plan.
- Sin Chaquopy, sin NDK/CMake, sin `libexec-shim` — eso es F4/F5.

## Qué NO incluye todavía

- Ningún módulo real (jadx/apktool/radare2/frida). El catálogo embebido
  (`assets/module_catalog.json`) está vacío.
- Parsing DEX (dexlib2) — F1.
- Nada de red real salvo el permiso declarado para cuando llegue F2.

## Compilar

Es un proyecto Gradle estándar (Groovy, no Kotlin DSL — la migración a
`.kts` es F3 según el propio plan). Debería compilar igual que el
extractor actual en tu pipeline de GitHub Actions / editor web:

```
./gradlew assembleDebug
```

El APK resultante se instala igual que el extractor: lista apps, pulsas
una, se extrae y se comparte — más el registro en una base Room local que
todavía no se muestra en pantalla (eso también es F1, pantalla de
resultados).

## Siguiente paso

F1: dexlib2 en el shell + pantallas de resultados + resto del esquema
Room + FTS. Lo seguimos en el próximo mensaje cuando quieras.
