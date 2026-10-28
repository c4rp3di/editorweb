# Precheck local de NetLens

Ejecutar desde la raíz del proyecto con `./tools/precheck/run_precheck.sh`.

El script compila con `kotlinc` los componentes de motor/reglas/TLS/MITM usando stubs mínimos de Android, ejecuta fuzzing y pruebas smoke de TLS, reglas y relé, valida recursos XML y corre comprobaciones estáticas.

Requisitos: Kotlin (`kotlinc`/`kotlin`), `javac`, Python 3 y jars BouncyCastle compatibles. Por defecto usa `/usr/share/java/bcprov-1.80.jar` y `/usr/share/java/bcpkix-1.80.jar`; sobrescribe las rutas con `BCPROV_JAR` y `BCPKIX_JAR` si es necesario.

**Límites:** no es un build de Android Gradle, los stubs no sustituyen a Android Framework y no prueba `VpnService.protect()` real, permisos, rutas, QUIC/DNS/TLS por red, IPv6 ni fuga de tráfico. El script no genera APK.
