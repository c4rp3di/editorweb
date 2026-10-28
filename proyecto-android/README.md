# NetLens 2.3.2-fix — proyecto Android para Fusion

NetLens es un proyecto didáctico de VPN local para observar flujos IPv4 TCP/UDP, atribuirlos a aplicaciones cuando Android lo permite y aplicar reglas de dominio basadas en SNI visible o `Host` en HTTP sin cifrar. Incluye controles para QUIC, DNS (DoH/DoT), lista de dominios por suscripción HTTPS, historial de sesiones y un MITM TLS experimental.

**Estado de esta entrega:** corrección de los errores que impedían compilar y del fallo que dejaba sin tráfico al activar la VPN. Queda pendiente la validación en dispositivo de la matriz de pruebas (ver abajo); no es un APK certificado.

## Errores corregidos en 2.3.2

1. **El proyecto no compilaba**: `NetworkTools.kt`, `DebugLog.kt`, `SessionHistory.kt` y `ListUpdateWorker.kt` se usaban en toda la app pero no estaban en el proyecto. Se han vuelto a incluir, con `NetworkTools.autoUpdate`, `savedUrl` y `lastUpdated`, que el resto del código ya usaba sin estar definidos.

2. **Con la VPN activa no funcionaban páginas ni apps** (causa raíz): Android crea el descriptor nativo de un `Socket` de forma perezosa, y `VpnService.protect()` sobre un socket sin descriptor devuelve `false`. Al tratar ese `false` como error fatal, **todas** las conexiones TCP se abortaban (y con ellas la navegación). Ahora:
   - Los sockets TCP se crean con `Socket()` y se **enlazan a un puerto efímero antes de `protect()`**, garantizando que exista el descriptor.
   - Los sockets UDP usan `DatagramSocket()`, que crea el descriptor en el constructor.
   - `protect()` se reintenta hasta 3 veces (el binder puede fallar de forma transitoria).
   - Si aun así no aplica, **no se aborta el tráfico**: el ámbito de la VPN (`addAllowedApplication`/`addDisallowedApplication`, verificado al inicio con error visible si falla) ya excluye al propio NetLens del TUN, por lo que un socket sin marcar no puede recursar hacia el túnel. El aviso queda registrado en el log de depuración.

3. **DNS con respuestas paralelas perdidas**: el receptor UDP cortaba tras la primera respuesta DNS. El resolvedor de Android lanza las consultas A y AAAA en paralelo desde el mismo puerto, así que la segunda respuesta legítima se descartaba. Ahora el receptor DNS escucha hasta el timeout de inactividad (5 s).

4. **Ventana TCP sin escalar (RFC 7323)**: el SYN del cliente anuncia un factor de escala que la pila no aplicaba, con lo que la ventana efectiva quedaba mal calculada (ralentización o atascos en descargas largas). Ahora se parsea la opción window scale del SYN y se aplica a todos los segmentos posteriores.

5. El socket remoto del MITM y el socket DoT siguen el mismo patrón (bind → protect → connect) y no abortan la conexión si `protect()` no aplica.

## Diagnóstico local

En **Herramientas → Depuración: ver / copiar log** se consulta el estado actual de VPN, MITM, QUIC, reglas y DNS, junto con hasta 500 eventos recientes: errores de inicio, lector TUN, `protect()` TCP/UDP, conexión upstream, clasificación TLS, bloqueos por reglas, UDP y QUIC. No registra payloads; sí puede incluir IPs, dominios y paquetes. Revísalo antes de compartirlo.

## Prueba del filtro por dominio

1. Compila e instala el proyecto en un dispositivo de pruebas propio.
2. Mantén inicialmente **MITM TLS** y **Bloquear QUIC** desactivados.
3. En **Herramientas → Protección**, activa las reglas y añade una regla como `-example.com`.
4. Inicia la captura y prueba solo con una aplicación/dominio de laboratorio bajo tu control.
5. Comprueba que un SNI visible de `example.com` se bloquea, `notexample.com` no se bloquea por coincidencia parcial, y una regla más específica como `+safe.example.com` permite ese subdominio. Si dos reglas cubren el mismo dominio, la regla por paquete tiene prioridad sobre la global.
6. Desactiva la regla y verifica que el tráfico vuelve a pasar.

## Significado de los controles

- **Todas las apps / selector:** define qué aplicaciones entran al túnel. Si seleccionas una app concreta, las demás usan su conexión de red normal.
- **Bloquear QUIC:** descarta UDP al puerto 443. La app puede intentar TCP como alternativa, pero depende de su implementación.
- **MITM TLS:** termina y vuelve a establecer TLS para una app objetivo de laboratorio. Requiere confiar en la CA local; no funciona con todas las apps y no elude certificate pinning.
- **Ocultar DNS:** solo oculta filas DNS en la vista en vivo; no cifra por sí mismo el DNS.
- **Pausar:** pausa la actualización de la vista, no la captura.
- **Protección:** reglas/listas de dominio. No es lo mismo que iniciar la VPN.
- **DoH/DoT:** resuelve por DNS cifrado (Cloudflare) las consultas UDP/53 capturadas; no afecta al DNS cifrado interno de otras apps. Sin fallback silencioso al DNS en claro.
- **Actualización periódica:** WorkManager re-descarga la lista HTTPS cada ~24 h si hay una suscripción válida.

## Límites importantes

- **IPv6 no se inspecciona en esta versión.** La ruta de VPN configurada es IPv4; no debe tratarse como cortafuegos integral ni como garantía de ausencia de fugas.
- La implementación de TCP en espacio de usuario sigue siendo simplificada: no tiene cola completa de retransmisión de los datos enviados al cliente ni reensamblado fiable de segmentos fuera de orden en todos los casos.
- Se descartan paquetes IPv4 fragmentados y datagramas UDP demasiado grandes para el formato de salida actual.
- El bloqueo HTTPS normal trabaja por hostname/SNI visible, no por URL, ruta, cabeceras ni cuerpo cifrado. ECH o SNI ausente puede ocultar el nombre real.
- El búfer de clasificación se limita a 64 KiB; si se supera, se reenvía sin clasificar para evitar memoria ilimitada.
- DoH/DoT fallidos no tienen fallback silencioso al DNS en claro; el error se registra.
- MITM sigue siendo experimental; úsalo solo con apps/servidores propios o con autorización y datos ficticios.

## Matriz mínima de validación en dispositivo

| Caso | Criterio mínimo de aprobación |
|---|---|
| VPN detenida | Wi-Fi/datos y apps funcionan con normalidad. |
| Todas las apps, reglas off, MITM off | HTTP, HTTPS y DNS funcionan; ninguna conexión abortada. |
| Una sola app seleccionada | Esa app se captura; las demás quedan fuera del túnel. |
| Reglas on/off | `-example.com` bloquea por SNI/Host visible; `+sub` permite; sin coincidencia parcial. |
| DNS normal / DoH / DoT | Las consultas reciben respuesta en cada modo; el error del proveedor queda visible. |
| MITM en app de laboratorio | Con la CA confiada, la conexión se descifra; con pinning, falla como se espera. |
| Detener/revocar/iniciar | Red se recupera; el ámbito de captura no cambia en silencio. |
