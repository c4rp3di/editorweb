# LuzControl — monitorización, análisis y simulación de consumo eléctrico (UFD · Naturgy)

App Android nativa (Kotlin + Jetpack Compose, Clean Architecture + MVVM, offline-first).
Paquete: `com.luzcontrol.app` · minSdk 26 · targetSdk/compileSdk 34.

## Compilación (compatible con Fusion / GitHub Actions)
- AGP 8.5.0, Kotlin 1.9.24, KSP 1.9.24-1.0.20, Hilt 2.51.1, Compose compiler 1.5.14, JDK 17, Gradle 8.7.
- Groovy DSL (`build.gradle`), módulo único `:app`. `./gradlew assembleDebug`.

## Capas
- `domain/`: modelos puros, interfaces de repositorio y casos de uso (coste, anomalías, standby, desplazamiento, factura, potencia).
- `data/`: Room (6 entidades), Retrofit (Datadis, ESIOS, REE), repositorios, WorkManager, notificaciones, widget, exportación CSV/PDF, credenciales cifradas.
- `ui/`: 5 pestañas (Inicio, Consumo, Precios, Facturas, Ajustes), estados Loading/Success/Stale/Empty/Error, gráficas propias en Canvas.

## Puntos a revisar antes de publicar
1. **Datadis (según el manual oficial de la API privada)**: login `POST nikola-auth/tokens/login` (form `username`/`password`, DNI/NIE en mayúsculas) y `GET api-private/api/get-supplies | get-contract-detail | get-consumption-data | get-max-power` con `Authorization: Bearer`. Fechas `AAAA/MM`. **Datadis no permite repetir la misma consulta en 24 h ni las que den error**: `ApiCallGuard` lo respeta (nunca repite antes de tiempo, sin reintentos automáticos de consultas, sin repetir logins rechazados). Las consultas omitidas usan los datos ya guardados.
2. **Certificate pinning**: `data/remote/NetworkConfig.kt` trae las listas de pines vacías (un pin erróneo bloquearía la app). Rellénalas con los hashes SPKI (instrucciones en el archivo).
3. **Parámetros de factura** (`BillingParams`): valores orientativos 2.0TD editables en Ajustes; contrástalos con una factura real.
4. **UFD directo**: no incluido (feature flag deshabilitada), como indica el plan.
5. **Términos de Datadis**: comprueba sus condiciones de uso para apps de terceros antes de distribuir en Play Store.
6. Los precios PVPC sin token salen de `apidatos.ree.es`; con token personal se usa ESIOS (indicadores 1001 y 600, península).
7. Tests unitarios en `app/src/test` (JUnit 5, MockK). Fusion no compila tests; ejecútalos con `./gradlew testDebugUnitTest`.
