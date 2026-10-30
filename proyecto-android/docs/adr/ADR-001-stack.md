# ADR-001 — Stack y tooling

**Estado:** ACORDADO (R0003; ajuste de DSL en R0004) · **Base:** NIA-MAESTRO

- Kotlin Android + core **Kotlin/JVM puro** (módulo `:contracts` no
  importa Android). Los contratos no dependen de implementaciones.
- Gradle con **Groovy DSL (`.gradle`)**: el pipeline de compilación de
  Fusion espera Groovy; el importador avisa de `.kts`. Se reevaluará si
  Fusion pasa a soportar Kotlin DSL sin fricción.
- **Canvas** (`android.graphics.Canvas`) como primer backend de dibujo
  propio. Sin WebView/GeckoView/Chromium/WebKit ni JS externo, sin
  fallback oculto.
- Intérprete JS **sin JIT** en las fases iniciales.
- Pocos módulos Gradle con paquetes lógicos: `:contracts`, `:app`
  (baseline); después `:engine-core`, `:harness`.
- Versiones: Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, JVM 17.
- Cambios Rust/C++/GPU requieren ADR propio con mediciones, no decisión
  individual.
