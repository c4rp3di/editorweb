# Navegador NIA — baseline 0.1.0 (código R0001)

Navegador Android con motor HTML/CSS/layout y JavaScript **propios** (sin
WebView, GeckoView, Chromium, WebKit ni JS externo). Este ZIP es la
baseline inicial de código del plan maestro (NIA-MAESTRO, revisión
documental R0004) e incluye:

- **T002** — Módulo `:contracts` (Kotlin JVM puro): ids tipados,
  `Outcome`/`ErrorInfo` con códigos cerrados y retryabilidad, handles con
  generación (`Handle`, `GenerationClock`, `HandleRegistry`), cancelación
  cooperativa (`CancellationTokenSource`, deadlines con reloj inyectable) y
  sobres IPC versionados (`IpcEnvelope`). Tests con kotlin-test.
- **T003 (inicio)** — `:app` mínima: `MainActivity` con primer dibujo
  propio sobre `android.graphics.Canvas`. Sin WebView ni layout XML.

## Estructura

```
settings.gradle / build.gradle / gradle.properties   raíz Gradle (Groovy DSL)
contracts/          módulo Kotlin JVM puro (contratos + tests)
app/                aplicación Android mínima
docs/adr/           ADR-001 (stack), ADR-008 (licencia), ADR-011 (plataforma)
.github/workflows/  contracts-tests.yml (CI: tests + APK debug)
```

## Importar en Fusion

Abrir → **Desde el dispositivo (.zip)** → selecciona este archivo.
Fusion lo detecta como proyecto Android (`AndroidManifest.xml` + `.kt` +
`.gradle`). Los archivos raíz, `contracts/` y `docs/` se conservan;
el workflow queda registrado en el panel de workflows.

## Compilar y probar

- **APK**: compila desde Fusion vía GitHub (el editor genera su workflow
  de APK nativa; con AGP 8.7.3 usa Gradle 8.9 automáticamente).
- **Tests del módulo `:contracts`**: workflow `contracts-tests`
  (`.github/workflows/contracts-tests.yml`) — ejecuta
  `gradle :contracts:test` y también un smoke build del APK debug,
  subiendo el APK como artefacto.

## Stack (ADR-001 / ADR-011)

Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.9 · Groovy DSL · minSdk 26 (Android
8.0) · targetSdk/compileSdk 35 (subida a 36 planificada cuando no añada
fricción de build) · JVM 17 · paquete `app.nia.navegador` (provisional,
ADR-008).

## Licencia (ADR-008)

Código cerrado. Sin permiso de redistribución. Distribución: APK manual
(sin Play Store). Las claves de firma de release NO se suben al repo:
el usuario las custodia fuera del proyecto.
