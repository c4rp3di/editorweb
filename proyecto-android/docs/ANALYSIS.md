# Pipeline de análisis

Ver `AnalysisOrchestrator.kt` — este documento es el mapa de qué fase
hace qué y de dónde sale cada tabla de Room, para no tener que leer el
código entero para ubicarse.

| Fase | Qué hace | Necesita | Puebla |
|---|---|---|---|
| [1] Ingesta | copia el APK del dispositivo a `cacheDir` | — | `AnalysisEntity` |
| [2] DEX | parsing con dexlib2: clases/métodos/campos | — | (solo cuenta, no persiste por clase) |
| — strings | extrae cadenas imprimibles, regex de URLs/secretos | — | `EndpointEntity`, `SecretEntity` |
| [3] Manifest | permisos + componentes exportados vía `PackageManager` | — | `PermissionEntity`, `ComponentEntity` |
| [4] Python | androguard (firma/certificado) + quark (scoring, si hay reglas) | Chaquopy (F5) | `FindingEntity` |
| módulo jadx | decompila a Java | módulo instalado | `FindingEntity` (meta) |
| módulo apktool | decodifica recursos completos | módulo instalado | `FindingEntity` (meta) |
| [5] Nativo | lista `.so`; análisis profundo con radare2 si está instalado | módulo radare2 (opcional) | `NativeLibEntity` |
| [6] Dinámico | SSL unpinning + log de URLs, **solo a propósito** | módulo frida-server + root | `RuntimeEventEntity` |
| [7] Correlación | reglas mínimas (ver abajo) + marca endpoints vistos en runtime | — | `FindingEntity`, `EndpointEntity.calledInRuntime` |
| [8] Reporte | exporta todo a HTML/JSON/SARIF | — | (no persiste, genera un fichero) |

## Reglas de correlación actuales (mínimas, a propósito)

El plan original hablaba de un "grafo" de correlación por hash de
URL/string — lo que hay implementado en F1-F7 es mucho más simple, y es
una decisión deliberada: cada regla extra es superficie nueva para
probar, y las que hay ya dan señal útil sin necesitar un motor de reglas
aparte.

- Hay secretos candidatos → `Finding` HIGH
- Hay endpoints sin TLS (`http://`) → `Finding` MEDIUM
- Hay componentes exportados → `Finding` LOW
- Hay permisos `dangerous` → `Finding` INFO
- El DEX solo está firmado v1 (sin v2/v3) → `Finding` MEDIUM (F5, androguard)
- quark-engine encuentra reglas que coinciden → `Finding` MEDIUM (si hay reglas empaquetadas)
- Una URL vista en runtime (F6) coincide con un endpoint ya visto
  estáticamente → se marca `calledInRuntime = true` en ese `EndpointEntity`
  (no se crea un Finding nuevo por esto; es información, no una alerta)

Si quieres extender esto, el sitio es la sección "[7] Correlación mínima"
de `AnalysisOrchestrator.run()` — cada regla nueva es solo construir un
`FindingEntity` más a partir de datos que ya están en las otras tablas.
