# Compilar

## El shell

```
./gradlew assembleDebug      # desarrollo
./gradlew assembleRelease    # firmado en CI, ver .github/workflows/build-shell-apk.yml
```

Requisitos a partir de F4: NDK (r26b) para `libexec-shim.so` si no hay un
`deps-shim-latest` ya publicado del que descargarlo. A partir de F5:
acceso a `https://chaquo.com/maven` para el plugin y los wheels de
Python. Ninguno de los dos hacía falta en F0-F3.

## CI (GitHub Actions)

Orden recomendado la primera vez, de cero:

1. `build-shim.yml` — compila `libexec-shim.so` y lo publica como
   `deps-shim-latest`
2. `build-module-jadx.yml`, `build-module-apktool.yml`,
   `build-module-radare2.yml`, `build-module-frida.yml` — uno por módulo,
   cuando quieras tenerlo disponible (ninguno es obligatorio para que el
   shell compile y funcione)
3. `publish-catalog.yml` — se dispara solo al terminar
   `build-module-jadx.yml` (ver el `workflow_run` en ese archivo); para
   los demás módulos, dispáralo a mano (`workflow_dispatch`) después de
   publicarlos, o cambia el `workflow_run` para que escuche a los cuatro
4. `build-shell-apk.yml` — compila y firma el shell, descargando el shim
   si ya existe

Secrets que hacen falta en el repo para que `build-shell-apk.yml` firme
el APK: `SIGNING_KEY` (keystore en base64), `KEY_ALIAS`,
`KEY_STORE_PASSWORD`, `KEY_PASSWORD`. Sin ellos, usa `assembleDebug` en
local o quita el paso de firma del workflow.

## Antes de tu primer build, revisa estos archivos (todos tienen avisos `⚠️`)

- `modules/Config.kt` — tu usuario/repo real de GitHub
- `.github/workflows/build-module-jadx.yml` — formato del tag de release de jadx
- `.github/workflows/build-module-apktool.yml` — formato del asset de apktool
- `.github/workflows/build-module-radare2.yml` — binario Android de radare2 (el más incierto de los tres JVM/nativos)
- `python/frida_runner.py` y `modules-src/frida-wrapper/` — control on-device de Frida (el más incierto de todo el proyecto)

## Tests

No hay tests automatizados todavía — quedó fuera de alcance de F0-F7 a
propósito (cada fase ya tenía bastante superficie nueva). Si añades
tests, estos son los sitios con más valor por esfuerzo, de más a menos
obvio:
- `StringScanner` (F1): regex de endpoints/secretos, fácil de testear sin Android
- `ModuleCatalog.parse` / `ModuleDownloader` (F2): verificación SHA-256, zip-slip
- `ReportGenerator` (F7): que el JSON/SARIF generado sea válido contra su schema
