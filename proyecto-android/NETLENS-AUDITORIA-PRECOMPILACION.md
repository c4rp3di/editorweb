# NetLens — auditoría previa a una nueva compilación

> **Actualización de esta auditoría (candidato 2.3.0-audit, 28/10/2026):** se aplicaron cambios a los hallazgos P0/P1 listados abajo: se crean sockets de salida con `SocketChannel`/`DatagramChannel` antes de `protect()` y `connect()`, el fallo de protección queda visible, MITM enlaza el listener sincrónicamente, los cambios de modo MITM se rechazan durante captura, errores del ámbito de apps abortan el inicio y HTTP/1.0 sin `Host` completo ya no se retiene. Además, las pruebas locales del relé cubren SNI permitido/bloqueado, HTTP Host dividido, HTTP/1.0 sin Host, protocolo desconocido y bloqueo UDP/443; se añadieron precedencia de reglas por paquete, prueba del ciclo start/stop de MITM y 16 comprobaciones estáticas. La prueba de ciclo de vida detectó una carrera entre `accept()` y `stop()`, ya corregida en este candidato. **Esto no demuestra que `protect()` funcione en el dispositivo ni sustituye la compilación Android.** El ZIP sigue siendo candidato para validación en dispositivo, no release verificada.

**Fecha:** 28/10/2026  
**Versiones comparadas:** fuente original adjunta, NetLens 2.2, 2.2.1 y 2.2.2.  
**Estado:** revisión estática y pruebas locales parciales; no es una certificación de la app ni una compilación Android.

## Conclusión

**No recomiendo compilar ni instalar NetLens 2.2.2 como solución del problema todavía.** La revisión confirma que la modificación que hizo que el fallo de `VpnService.protect()` cerrase las conexiones no estaba suficientemente validada. El cambio de 2.2.2 (`bind()` antes de `protect()`) intenta inicializar el descriptor del socket, pero mantiene el mismo cierre inmediato si `protect()` devuelve `false`; no demuestra que el problema quede resuelto.

La versión original ignoraba el valor booleano de `protect()`. La revisión 2.2 lo convirtió en una excepción fatal. El texto que el usuario ve, «No se pudo excluir el socket TCP de la VPN», procede directamente de esa comprobación. Eso identifica el punto de fallo del flujo; **no determina por sí solo por qué Android devuelve `false` en ese dispositivo**.

## Hallazgos priorizados

### P0 — La protección del socket aborta el relay

En `Engine.kt`, `protect(Socket)` y `protect(DatagramSocket)` llaman a `check(svc.protect(...))`. Si devuelve `false`, el TCP saliente aborta en `TcpConn.begin()`; el flujo se cierra y se genera un RST. UDP también puede quedar descartado al fallar la creación del socket. La misma comprobación afecta a DoT y al socket remoto del MITM.

La documentación oficial de Android dice que `protect()` devuelve `true` si tuvo éxito y que puede fallar si la aplicación VPN no está preparada o ha sido revocada. Por ello, añadir `bind()` no es prueba suficiente de que la llamada deba funcionar.

### P1 — Modo MITM: inicio y cambios durante la captura no están coordinados

- `NetLensVpnService` inicia el listener MITM en un hilo y no espera a que el puerto local esté listo. Una conexión HTTPS inmediata puede intentar conectar a `127.0.0.1:8888` antes de que el listener escuche.
- El interruptor MITM modifica `Store.mitmEnabled` en caliente, pero el proxy se crea solamente al arrancar el servicio. Activarlo después de iniciar puede dirigir nuevas conexiones a un puerto que no tiene listener; desactivarlo no detiene necesariamente el proxy ya abierto.
- El `SSLSocket` del lado del cliente tiene un `soTimeout` de 20 segundos y el relay usa esperas `join()` de duración fija. Esto puede cortar conexiones TLS o sesiones persistentes inactivas/largas.
- El MITM termina y vuelve a establecer TLS, pero los datos se copian como streams cifrados/descifrados; no implementa inspección de URL, cabeceras HTTP completas ni cuerpos. No debe presentarse como filtro por URL/contenido.

### P1 — La VPN no cubre todos los paquetes IP

- El builder instala `0.0.0.0/0`, pero no instala una dirección/ruta IPv6 y `Engine.handle()` acepta únicamente paquetes IPv4. El tráfico IPv6 no queda filtrado por este motor.
- `Engine.handle()` descarta los paquetes IPv4 con fragmentos (`MF` u offset distinto de cero) sin reensamblarlos. Los flujos que dependan de fragmentación pueden fallar.
- El receptor UDP descarta datagramas de más de 1472 bytes en vez de fragmentarlos de forma compatible con el MTU o gestionarlos mediante un mecanismo adecuado.

### P1 — La pila TCP de usuario necesita pruebas de fiabilidad

El motor implementa su propio estado TCP. Se aceptan principalmente segmentos de cliente en orden y no se observa un buffer de reordenamiento; el lado servidor emite segmentos al cliente, pero no mantiene una cola de retransmisión de los datos enviados ni retransmite por timeout los segmentos no confirmados. Esto puede funcionar en pruebas sencillas y aun así colgarse o perder datos en redes con pérdida, latencia o reordenación.

### P2 — Fallos de configuración que se ocultan

`NetLensVpnService` captura excepciones de `addAllowedApplication()` / `addDisallowedApplication()` y las ignora. Si falla la configuración del ámbito de aplicaciones, la VPN puede arrancar con un ámbito distinto del elegido. Ese error debe mostrarse y el inicio debe abortarse.

### P2 — HTTP sin `Host` puede quedarse retenido

El clasificador mantiene en búfer una petición HTTP reconocida si no encuentra `Host`. En una petición HTTP/1.0 válida sin ese encabezado, la app puede esperar respuesta sin cerrar el flujo; NetLens puede no reenviar la petición hasta alcanzar el límite del búfer o recibir FIN.

### P2 — Los controles de interfaz no son controles de transporte

- «Ocultar DNS» oculta filas DNS en la vista principal; no cifra ni bloquea por sí mismo las consultas DNS.
- «Pausar» detiene la actualización de la lista visible en la pestaña En vivo; no detiene la captura ni el reenvío de paquetes.
- La protección por reglas está separada del estado de la VPN. `NetworkTools.enabled` vale `false` de forma predeterminada; las reglas no bloquean destinos hasta activar Protección.
- «Bloquear QUIC» descarta UDP al puerto 443. Muchas apps prueban TCP como alternativa, pero no puede garantizarse que todas hagan fallback ni que todo QUIC use el puerto 443.
- DoH/DoT no tiene fallback silencioso a DNS en claro por diseño; si falla el proveedor o la conexión cifrada, las consultas fallan y debe quedar visible el error.

## Matriz que debe pasar antes de considerar un APK de prueba

| Caso | Configuración | Criterio mínimo de aprobación |
|---|---|---|
| A | VPN detenida | Wi-Fi/datos y apps funcionan con normalidad. |
| B | Todas las apps, reglas desactivadas, MITM apagado, QUIC permitido, DNS normal | HTTP, HTTPS y una conexión TCP no web funcionan; no aparece error de exclusión de socket. |
| C | Una sola app seleccionada | Esa app se captura; una app no seleccionada sigue fuera del túnel. El ámbito configurado debe coincidir con el elegido. |
| D | Protección/reglas activadas y desactivadas | `-example.com` bloquea `example.com` y subdominios cuando el SNI es visible; no bloquea `notexample.com`; una excepción específica funciona según precedencia documentada. |
| E | HTTPS con ClientHello en varios segmentos/registros | Dominio visible correctamente detectado y decisión de bloqueo antes de enviar bytes iniciales. |
| F | SNI ausente / ECH | No afirmar que se conoce el dominio real; comportamiento de paso/cierre explícito y consistente con la política elegida. |
| G | QUIC permitido y bloqueado | Permitido: datagramas UDP/443 se transmiten. Bloqueado: se descartan; verificar por separado si la app hace fallback a TCP. |
| H | DNS normal, DoH y DoT | Consultas válidas reciben respuesta en cada modo; error del proveedor visible; no hay sockets filtrados/atascados. |
| I | MITM apagado y encendido en app de laboratorio autorizada | Con MITM apagado, HTTPS directo funciona. Con MITM, listener listo antes del primer flujo, CA de laboratorio confiada y validación del servidor remoto correcta. Probar app con pinning como caso negativo esperado. |
| J | MITM con inactividad y sesión larga | La conexión no se corta solo por 20 s de inactividad o por una espera fija. |
| K | Detener, revocar permiso VPN y volver a iniciar repetidamente | Los sockets/threads se cierran, la red se recupera y el ámbito de captura no cambia de manera silenciosa. |
| L | IPv6, paquetes fragmentados y UDP grande | Hasta que exista soporte real, las limitaciones deben quedar explícitas; no afirmar filtrado integral. |

## Comprobaciones ejecutadas durante la auditoría inicial

Las cifras de esta sección corresponden a la auditoría inicial de las versiones 2.2.x. Los resultados actualizados para el candidato 2.3.0-audit están en `VALIDACION.md`; el precheck ampliado añade pruebas de relé HTTP/QUIC y 16 comprobaciones estáticas.

- Comparación de los ZIP original, 2.2, 2.2.1 y 2.2.2; el cambio de `protect()` en 2.2 y el `bind()` añadido en 2.2.2 se identificaron en el código.
- Integridad del ZIP 2.2.2: correcta.
- XML de manifiesto, strings y tema: parsean correctamente.
- Prueba local directa del parser TLS (`kotlinc` disponible): **6 suites aprobadas**, incluyendo SNI/ALPN/TLS 1.3, ClientHello parcial, ClientHello entre registros, ECH, `Host` HTTP y 2.000 entradas aleatorias malformadas.
- No hay Gradle Wrapper, ejecutable Gradle ni Android SDK en este entorno. No se pudo ejecutar el build Android completo ni probar sockets, rutas VPN, DNS, QUIC o TLS sobre un dispositivo/emulador.

## Criterio de salida actualizado

La revisión del código y el precheck local del candidato 2.3.0-audit se completaron. El paso siguiente sigue siendo la compilación Android y la matriz descrita en `VALIDACION.md` en un dispositivo/emulador. No etiquetar el ZIP como «validado» hasta superar esas pruebas. Fusion-21.html se mantiene sin modificar.
