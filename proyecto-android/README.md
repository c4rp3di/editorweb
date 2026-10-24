# Perplexus 3D — Android nativo (v1.0.0)

Laberinto esférico 3D con física de cuerpo rígido en un **sistema de referencia no inercial**: la esfera rota con el
móvil y la bola siente gravedad, fuerza centrífuga, Coriolis y Euler. Implementa el *Plan técnico v1.0* completo.

* Kotlin 1.9.22 · AGP 8.2.2 · Gradle ≥ 8.2 (workflow de respaldo usa 8.5) · JDK 17
* minSdk 24 · targetSdk/compileSdk 34 · OpenGL ES 3.0 · **sin dependencias de runtime** (solo JUnit para tests)
* Paquete `com.perplexus.game` · APK debug firmado con `apk-build/debug.keystore` si existe en la raíz

## Compilar
`gradle assembleDebug` (o el workflow `build-apk-nativo.yml` del editor). No se incluye `gradlew`/`gradle-wrapper.jar`
(binario): si tu workflow lo exige, genera el wrapper con `gradle wrapper --gradle-version 8.5`.
Tests de JVM: `gradle :app:testDebugUnitTest`.

## Cómo se juega
1. Calibra (móvil quieto 1 s). 2. Elige nivel. 3. Gira el móvil: la bola rueda por tubos huecos.
Tocar la esfera exterior = caer al vacío (derrota); llegar a la esfera verde = victoria.
**Truco del nivel 1:** gira el móvil sobre su eje horizontal (como un tornillo): la bola avanza por la hélice.
Gestos: dos dedos = órbita, pinza = zoom, doble toque = recentrar, pulsación larga 3 s esquina sup. izq. = debug.

## Estructura
```
app/src/main/java/com/perplexus/game/
  math/       Vec3 Quat Mat3 Mat4 AABB BVH Transform MathUtils
  physics/    RigidBody Forces(fuerzas ficticias) PhysicsWorld Broadphase(BVH) Narrowphase CollisionSolver
              Shapes: Capsule Triangle Sphere Box Tube(+TubeJoint, Shell)
  input/      SensorFusion(Mahony) OrientationEstimator Calibrator SensorMonitor SampleRing(lock-free) controladores
  render/     GameRenderer Shader Mesh Camera Scene ShadowMap PostProcess(bloom+FXAA+viñeteado) ParticleSystem
              TubeMesh LevelMesh ProceduralMarble/Noise
  levels/     Json(propio) Level LevelFormat LevelLoader LevelValidator LevelGenerator
  ui/ core/ lifecycle/ audio/ storage/ diag/   (pantallas, estados, sensores, sonido, prefs, log/crash/profiler)
app/src/main/assets/levels/   8 niveles JSON + index.json (se pueden añadir más en Android/data/<pkg>/files/levels/*.json)
app/src/test/                 tests JUnit (math, física, sensores, niveles, escenarios completos)
tools/                        gen_levels.py, gen_sounds.py, physics_smoke.py (port Python de la física)
```

## Formato de nivel (JSON)
`polyline` (tubos huecos encadenados con codos automáticos; `open` = ranura por la que cae la bola, escalar o por
tramo; `branches` = bifurcaciones en Y), `tube`, `capsule`, `triangle`, `sphere`, `box`. Ver `levels/LevelFormat.kt`.

## Desviaciones deliberadas respecto al plan (y por qué)
* **Tubos huecos** (la bola rueda por dentro) con ranuras, codos y bifurcaciones, en vez de cápsulas macizas: es lo que
  hace jugable el concepto Perplexus. Las cápsulas macizas siguen disponibles.
* **Caer al vacío = tocar la esfera exterior** (la esfera es física, RF-05, y a la vez letal, RF-06/08).
* **CCD por subdivisión adaptativa** del paso (hasta 16 subpasos) en lugar de TOI analítico.
* **Rodadura con SoundPool** (loop + `setRate`) en lugar de MediaPlayer: cambia el rate sin artefactos.
* **FXAA en vez de MSAA** (la escena se dibuja a textura para el post-proceso).
* **Cámara**: FOV adaptado al aspecto para que la esfera quepa; zoom como factor sobre la distancia base y nunca
  dentro de la esfera (los valores 0.5R–2.5R del plan situarían la cámara dentro).
* Orientación del cuaternión: q = dispositivo→mundo (Z arriba), `q = fromTwoVectors(a_n, ẑ)` en la calibración.
* Editor in-app de niveles: no incluido (el plan lo marca opcional); sí hay generador procedural ("Nivel aleatorio").

## Validación hecha sin compilador Kotlin
Esta entrega se escribió sin poder ejecutar `kotlinc`/Gradle. Se validó: balance sintáctico de los 87 archivos,
geometría y accesibilidad de los 8 niveles (`tools/gen_levels.py`) y la física/efecto tornillo con un port en Python
(`tools/physics_smoke.py`, niveles 1, 2, 5 y 6 se completan con un giro constante). **Es probable que la primera
compilación muestre algún error tipográfico**: el log de Actions suele señalarlo directamente.
