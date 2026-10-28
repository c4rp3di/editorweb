# Validación previa a Android — NetLens 2.3.0-audit

Fecha: 28/10/2026

## Estado

**Candidato de código revisado; no es APK validado.** El precheck local pasó. No hay Gradle ejecutable ni Android SDK en el entorno, por lo que no se ha hecho una compilación Android completa ni una prueba real de la VPN.

## Pruebas ejecutadas en este entorno

1. `Dpi.kt`, `Store.kt`, `Engine.kt`, `NetworkTools.kt`, `MitmProxy.kt` y `DpiTlsTest.kt` compilaron juntos con `kotlinc` usando stubs mínimos de Android y los jars BouncyCastle disponibles. No equivale a compilar contra Android Framework/Gradle.
2. El parser TLS procesó 2.000 entradas aleatorias malformadas sin lanzar excepciones.
3. Se ejecutaron las cinco pruebas de `DpiTlsTest.kt`: SNI/ALPN/TLS 1.3, ClientHello parcial, fragmentación entre registros, ECH y entradas malformadas.
4. Las pruebas de reglas cubrieron dominio exacto y subdominios, frontera frente a `notexample.com`, precedencia por especificidad, regla específica por paquete frente a regla global y alcance por aplicación.
5. La prueba del relé TCP verificó que un ClientHello permitido repartido entre dos segmentos se reenvía completo; uno bloqueado por SNI no se encola; una petición HTTP cuyo `Host` llega en el segundo segmento espera la cabecera y se bloquea; una petición HTTP/1.0 completa sin `Host` se reenvía; un protocolo pequeño desconocido no se atasca.
6. Se probó el ciclo de vida del listener MITM en JVM: `start()` deja el puerto loopback disponible antes de retornar y `stop()` cierra el listener; también se reforzó el cierre frente a una carrera con `accept()`. Esto no prueba un handshake TLS real ni sockets de Android.
7. Se probó la rama UDP/443 con `Bloquear QUIC` activado: se registra el bloqueo y no se abre/protege un socket saliente. El comportamiento de QUIC real y el fallback de las apps siguen pendientes en Android.
8. Los stubs verificaron el tratamiento de `protect()` como verdadero/falso para TCP/UDP y que el error no se ignora. Esto **no** prueba el resultado de `VpnService.protect()` en Android real.
9. Las 16 comprobaciones estáticas de `tools/precheck/static_precheck.py` pasaron, incluidos orden de creación/protección/conexión de sockets, arranque/cierre del listener MITM, comprobación de identidad HTTPS remota, alcance de apps, reglas y limitaciones anunciadas.
10. `AndroidManifest.xml`, `strings.xml` y `themes.xml` parsean como XML válido.

Para repetir las pruebas locales, desde la raíz del proyecto:

```bash
./tools/precheck/run_precheck.sh
```

El script necesita `kotlinc`, `kotlin`, `javac`, Python 3 y los jars de BouncyCastle. Si sus rutas difieren, se pueden definir `BCPROV_JAR` y `BCPKIX_JAR`. El script no genera APK.

## Pendiente de comprobar en Android real

- Compilación de la app con Android Gradle Plugin y las dependencias de la herramienta Fusion.
- Resultado real de `VpnService.protect()` para sockets TCP/UDP, incluida la causa del error observado en el dispositivo original.
- Conectividad con todas las apps y con una sola app seleccionada; detención, revocación y reinicio de la VPN.
- HTTP/HTTPS normales, sesiones TCP largas, pérdida/retransmisión y segmentos fuera de orden.
- DNS normal, DoH y DoT, incluidos errores de proveedor.
- QUIC permitido/bloqueado y fallback real de distintas apps.
- MITM con una app de laboratorio autorizada, CA de laboratorio, validación del servidor remoto, pinning y conexiones largas.
- IPv6, fragmentación IPv4 y datagramas UDP grandes. Esta versión no filtra IPv6, descarta fragmentos IPv4 y no gestiona todos los tamaños de UDP.

## Matriz de dispositivo obligatoria

| Prueba | Configuración | Criterio de aprobación |
|---|---|---|
| A | VPN detenida | Internet normal funciona. |
| B | Todas las apps, reglas off, MITM off, QUIC permitido, DNS normal | HTTP, HTTPS y una app no web funcionan; no aparece error `protect`. |
| C | Una app objetivo | Solo esa app pasa por la VPN; el resto mantiene el ámbito elegido. |
| D | Regla `-example.com` y excepción `+safe.example.com` | Se bloquea dominio/subdominios con SNI visible, no `notexample.com`, y la excepción respeta precedencia. |
| E | ClientHello en varios segmentos/registros | SNI visible se clasifica antes de reenviar los bytes iniciales. |
| F | ECH/SNI ausente | No se afirma conocer el hostname real; se informa la limitación. |
| G | QUIC off/on | UDP/443 se descarta cuando el control está activo; se comprueba aparte si la app hace fallback. |
| H | DNS normal/DoH/DoT | Las consultas reciben respuesta o muestran un error explícito; no hay fallback silencioso. |
| I | MITM off/on en app de laboratorio | Listener listo, CA adecuada y validación TLS remota correcta; fallos esperados por pinning/confianza identificados. |
| J | Parada/revocación/reinicio | Se cierran sockets/hilos y la red vuelve a funcionar. |
| K | IPv6, fragmentos y UDP grande | Se confirman límites; no se declara filtrado integral ni ausencia de fugas. |

## Importante

Este ZIP contiene **código fuente candidato para importar en Fusion**, no un APK validado. La corrección usa `SocketChannel`/`DatagramChannel` para crear el descriptor antes de protegerlo y conectarlo, pero solo una prueba real puede confirmar si esto resuelve el error de `protect()` en el teléfono. `Fusion-21.html` no se ha modificado.
