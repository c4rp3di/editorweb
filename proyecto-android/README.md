# NetLens 2.2-review — filtro local de red para Android

NetLens es un proyecto didáctico de Android que crea una VPN local, registra flujos TCP/UDP, atribuye conexiones a aplicaciones cuando Android lo permite, aplica reglas de dominio, inspecciona DNS/SNI y ofrece un MITM TLS experimental para laboratorios autorizados.

**Estado de la entrega:** código fuente revisado y pruebas aisladas de Kotlin ejecutadas. No hay APK ni compilación completa de Gradle confirmada: el entorno de revisión no dispone de Android SDK/Gradle. Todavía necesita pruebas en un dispositivo real.

## Mejoras de esta revisión

- El parser TLS procesa el ClientHello completo, incluidos mensajes fragmentados entre segmentos TCP o registros TLS, y extrae SNI, ALPN, TLS 1.3 y la señal ECH con validaciones de límites.
- El relay mantiene en búfer los primeros bytes TCP (hasta 64 KiB) mientras clasifica el protocolo y el Host/SNI. Si una regla bloquea el dominio, no encola ni reenvía esos bytes iniciales al socket de salida; si se permite, los reenvía en el orden original después de clasificar.
- Las reglas usan coincidencia por límites de dominio (dominio exacto o subdominio) y gana la regla explícita coincidente más específica. Si dos reglas tienen la misma especificidad, la de permitir gana.
- El filtrado de HTTP en claro espera al encabezado `Host` para aplicar las reglas.
- Si no se puede proteger un socket de salida con `VpnService.protect`, la conexión falla en lugar de continuar en un estado potencialmente recursivo.
- El proxy MITM obtiene el nombre de servidor desde `ExtendedSSLSession`/SNI en vez de tratar el `peerHost` como SNI, y activa la verificación de identidad HTTPS al conectarse al servidor remoto.
- La interfaz ya no afirma que puede detectar si el usuario instaló realmente la CA: Android no ofrece una comprobación simple y fiable de esa condición para este flujo.

## Cómo probar el filtro por dominio

1. Instala una build de depuración que tú mismo hayas compilado en un dispositivo de pruebas.
2. Abre **Herramientas → Protección** y déjala en **ACTIVA**.
3. En **Herramientas → Reglas guardadas**, añade por ejemplo `-example.com` y guarda.
4. Inicia la captura y prueba únicamente con una aplicación y dominios bajo tu control. `example.com` y sus subdominios deberían aparecer bloqueados cuando el SNI sea visible.
5. Prueba una excepción más específica, por ejemplo `+safe.example.com`, para comprobar la precedencia de reglas.
6. Consulta los detalles del flujo: el bloqueo por SNI debe aparecer como `HTTPS bloqueado antes de enviar el ClientHello (SNI)`.

La lista de suscripción y las reglas solo aplican cuando **Protección** está activa. Bloquear QUIC es un ajuste separado que afecta a UDP/443.

## Límites de HTTPS y seguridad

- El filtro genérico HTTPS bloquea por **dominio** (SNI), no por URL completa. No puede decidir por ruta, parámetros, cabecera HTTP o contenido del cuerpo cifrado sin una inspección MITM compatible.
- **ECH (Encrypted Client Hello)** puede ocultar el nombre real solicitado; el dominio exterior visible no siempre identifica el destino real. En ese caso no se garantiza un bloqueo por el nombre real.
- El MITM sigue siendo experimental. No filtra rutas ni cuerpos HTTP, puede romper aplicaciones modernas y no elude certificate pinning. Úsalo solo con aplicaciones propias o autorizadas y datos ficticios.
- IPv6 no se inspecciona completamente en esta versión y puede escapar del filtrado IPv4; NetLens no debe tratarse como cortafuegos integral ni como prueba de ausencia de fugas.
- Las aplicaciones pueden usar DNS cifrado, direcciones IP, protocolos propios o canales alternativos. SNI ausente, ECH o un flujo que no llegue a clasificarse puede impedir el filtro por nombre.
- El búfer de clasificación inicial está limitado a 64 KiB. Si se supera ese límite, el flujo se reenvía sin clasificación para evitar consumo ilimitado de memoria; por ello no es un filtro de seguridad con garantía absoluta.

## MITM TLS opcional (laboratorio)

1. Arranca NetLens y pulsa **Exportar CA**.
2. Instala el certificado CA desde los ajustes de seguridad de Android y comprueba la huella SHA-256 mostrada por NetLens.
3. Activa MITM solo para una app propia elegida como objetivo. Una app de pruebas debuggable puede configurar `network_security_config.xml` con `debug-overrides` y `certificates src="user"`.
4. No desactives la validación del certificado del servidor remoto. Las apps con certificate pinning o que no aceptan CAs de usuario pueden fallar correctamente.

La presencia de un registro MITM solo significa que se estableció el túnel TLS en ambos lados; **no significa que NetLens haya inspeccionado URLs o cuerpos HTTP**.

## Validaciones ejecutadas durante esta revisión

- Compilación Kotlin aislada de `Dpi.kt`, `Store.kt`, `Engine.kt` y `NetworkTools.kt` con stubs de Android.
- Prueba sintética del parser para SNI normalizado, ALPN, detección TLS 1.3, ClientHello fragmentado por TCP, ClientHello en varios registros TLS y señal ECH.
- Prueba de integración aislada del relay: un SNI bloqueado no se encola ni cuenta como enviado; un ClientHello permitido y dividido en dos segmentos se reenvía completo después de clasificar.
- Pruebas de reglas: coincidencia por subdominio, regla más específica, alcance por paquete y prevención de coincidencias engañosas como `notexample.com`.
- Compilación aislada de `MitmProxy.kt` con stubs de Android/BouncyCastle.

Estas pruebas no sustituyen un build Android real, pruebas en móvil, pruebas de rendimiento ni una auditoría de IPv6/fugas.
