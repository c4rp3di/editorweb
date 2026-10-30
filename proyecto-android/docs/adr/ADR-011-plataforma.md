# ADR-011 — Plataforma Android

**Estado:** ACORDADO (R0003; ajuste de targetSdk en R0004) · **Base:** NIA-MAESTRO

- **minSdk 26** (Android 8.0): cubre ~95% de dispositivos activos.
- **targetSdk/compileSdk 35** en la baseline: par AGP/Gradle probado y
  soportado por el pipeline de Fusion. La subida a 36 (Android 16) está
  planificada cuando no añada fricción de build; es un cambio de bajo
  riesgo posterior.
- Dispositivos: teléfonos y tablets ARM64.
- Teclado externo contemplado (DevTools y comandos lo aprovechan).
