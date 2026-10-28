# NetLens 2.6.0 — proyecto Android para Fusion

NetLens es un proyecto didáctico de VPN local para observar flujos IPv4 TCP/UDP, atribuirlos a aplicaciones cuando Android lo permite y aplicar reglas de dominio basadas en SNI visible o `Host` en HTTP sin cifrar. Incluye controles para QUIC, DNS (DoH/DoT), lista de dominios por suscripción HTTPS, historial de sesiones y un MITM TLS experimental.

**Estado de esta entrega:** sobre la 2.3.2-fix (que corrigió compilación y tráfico), la 2.4.0 añade las piezas de bloqueo de anuncios: lista recomendada por suscripción, anti-DoH, anti-ECH, neutralización de IPv6 y respuestas DNS bloqueadas sintéticas. La 2.4.1 corrige la lista recomendada: `small.oisd.nl/hosts` dejó de existir (HTTP 404), así que se usa el espejo GitHub de OISD small con respaldo automático en StevenBlack. La 2.5.0 amplía el lote recomendado a 3 listas fusionadas (OISD small + StevenBlack + HaGeZi Pro) con tolerancia a fallos por lista. La 2.5.1 suaviza el conmutador Bloquear ECH: los ClientHello con extensión ECH ya no se rechazan (el rechazo causaba reinicios de conexión que impedían cargar sitios con ECH); se dejan pasar y se clasifican por el SNI externo, que sigue aplicando el filtro por dominio. La 2.6.0 añade filtrado de rutas de petición dentro del túnel MITM con la nueva sintaxis `-dominio.com/ruta` (laboratorio). Queda pendiente la validación en dispositivo de la matriz de pruebas; no es un APK certificado.

## Novedades de 2.4.x, 2.5.x y 2.6.0 (bloqueo de anuncios en webs y apps)

1. **Lote recomendado de 3 listas fusionadas** (2.5.0): Herramientas → *Suscribir listas recomendadas (3 fusionadas)* descarga en serie y deduplica OISD small (`raw.githubusercontent.com/sjhgvr/oisd/.../domainswild2_small.txt`, ~59.000 dominios), StevenBlack (`raw.githubusercontent.com/StevenBlack/hosts/master/hosts`, ~76.000) y HaGeZi Pro (`raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt`, ~100.000): ~180.000 dominios de anuncios, rastreadores, telemetría y estafas. Si una lista falla, se conservan las demás (solo exige que al menos una descargue); la actualización periódica refresca el lote completo. El límite de descarga es de 8 MiB por lista. El motor de bloqueo (DNS, SNI, `Host`, QUIC temprano, TCP por IP/DNS) ya existía desde 2.3.x; ahora hay contenido listo para usar.

2. **Anti-DoH** (`Store.blockDoh`): las apps con DoH/DoT embebido resuelven solas y se saltan el filtrado por nombre. Se bloquean los dominios de resolutores cifrados conocidos (dns.google, cloudflare-dns.com y variantes, quad9, adguard, opendns, mullvad, etc.) y los puertos 853 TCP (RST) y UDP (descarte). El DoT/DoH interno de NetLens va con `protect()` y no se ve afectado.

3. **Bloquear ECH** (`Store.rejectEch`), doble capa:
   - Primaria: las consultas DNS de tipo HTTPS(65)/SVCB(64) se responden en local con NOERROR vacío, de modo que el navegador no recibe configuración ECH y el SNI del ClientHello queda visible y filtrable.
   - Suave (2.5.1): si aún llega un ClientHello con extensión ECH (0xFE0D), se deja pasar y se clasifica por el SNI externo; el rechazo anterior causaba reinicios de conexión (ERR_CONNECTION_RESET) que impedían cargar sitios con ECH. El filtro por dominio sigue aplicándose sobre el SNI externo.

4. **Neutralizar IPv6** (`Store.blockIpv6`, por defecto ON): sin rutas v6, las apps con IPv6 salían FUERA de la VPN y se saltaban todo el filtrado. Ahora el TUN enruta `2000::/3` y `fc00::/7`; el motor (solo IPv4) descarta esos paquetes con un aviso único en el log. Las apps dual-stack decaen a IPv4; los destinos solo-v6 quedan inaccesibles durante la captura (comportamiento esperado y documentado).

5. **DNS bloqueado con respuesta sintética** (`Dpi.dnsBlockedReply`): las consultas bloqueadas responden `0.0.0.0` (TTL 300) para A y NOERROR vacío para el resto (AAAA, HTTPS...), en lugar del NXDOMAIN anterior: las apps fallan rápido y sin reintentos agresivos.

6. **Filtrado de rutas en MITM** (2.6.0, laboratorio): nueva sintaxis de regla `-dominio.com/ruta` (solo globales, sin parte de paquete). Dentro del túnel MITM, cada petición HTTP/1.1 se evalúa contra la ruta: `-dominio.com/ads_videos/*` bloquea con 204 cualquier petición cuyo path empiece por esa ruta; sin `*` exige coincidencia exacta; `*` intermedio actúa de comodín. La query (`?...`) no se evalúa. **Solo aplican con MITM TLS activo**: sin MITM quedan inertes (el dominio se sigue filtrando por DNS/SNI con la regla sin ruta). Las peticiones con cuerpo no delimitable (chunked) o cabeceras desmesuradas pasan transparentes; los cuerpos y las respuestas no se inspeccionan. Requiere CA instalada y app objetivo única; el navegador DuckDuckGo probablemente rechazará la CA (probar con Chrome o Firefox importando la CA).

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
- **MITM TLS:** termina y vuelve a establecer TLS para una app objetivo de laboratorio. Requiere confiar en la CA local; no funciona con todas las apps y no elude certificate pinning. Desde 2.6.0, dentro del túnel filtra rutas de petición con reglas `-dominio.com/ruta`; no inspecciona cuerpos ni respuestas.
- **Ocultar DNS:** solo oculta filas DNS en la vista en vivo; no cifra por sí mismo el DNS.
- **Pausar:** pausa la actualización de la vista, no la captura.
- **Protección:** reglas/listas de dominio. No es lo mismo que iniciar la VPN.
- **DoH/DoT:** resuelve por DNS cifrado (Cloudflare) las consultas UDP/53 capturadas; no afecta al DNS cifrado interno de otras apps. Sin fallback silencioso al DNS en claro.
- **Actualización periódica:** WorkManager re-descarga la lista HTTPS cada ~24 h si hay una suscripción válida.
- **Bloquear ECH:** corta las consultas HTTPS(65)/SVCB(64) y, si llega un ClientHello con ECH, lo deja pasar clasificándolo por el SNI externo (desde 2.5.1 ya no se rechaza: rompía la carga de sitios con ECH).
- **Anti-DoH:** bloquea resolutores cifrados conocidos (dominio y puerto 853) para forzar el DNS del sistema, que pasa por la VPN.
- **Neutralizar IPv6:** enruta IPv6 al TUN y lo descarta; evita que las apps dual-stack se salten la VPN. Con destinos solo-v6, quedan inaccesibles durante la captura.

## Límites importantes

- **IPv6**: con *Neutralizar IPv6* activo, el espacio v6 se enruta al TUN y se descarta (no hay fuga fuera de la VPN, pero tampoco inspección v6; destinos solo-v6 no funcionan durante la captura). Con el conmutador apagado, IPv6 queda fuera de la VPN. No debe tratarse como cortafuegos integral ni como garantía de ausencia de fugas.
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
| Regla de ruta en MITM (2.6.0) | Con `-dominio.com/ruta/*` y CA confiada, la petición a esa ruta responde 204 y queda registrada como `PETICIÓN ... → 204`; el resto del dominio funciona. |
| Detener/revocar/iniciar | Red se recupera; el ámbito de captura no cambia en silencio. |
| Lista OISD suscrita | Con Protección activa, un dominio de la lista se bloquea por DNS (0.0.0.0) o SNI; uno legítimo pasa. |
| Anti-DoH activo | Una app con DoH embebido (p. ej. Chrome con «DNS seguro») vuelve al DNS del sistema; el 853 da RST. |
| Bloquear ECH activo | Las consultas tipo 65/64 responden vacío; si llega un ClientHello con ECH se clasifica por el SNI externo (2.5.1, sin reinicios de conexión). |
| Neutralizar IPv6 activo | Una web solo-v6 no carga durante la captura; una dual-stack usa IPv4 y funciona. |
