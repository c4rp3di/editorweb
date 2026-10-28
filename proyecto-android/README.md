# NetLens 2.3.0-audit — proyecto Android para Fusion

NetLens es un proyecto didáctico de VPN local para observar flujos IPv4 TCP/UDP, atribuirlos a aplicaciones cuando Android lo permite y aplicar reglas de dominio basadas en SNI visible o `Host` en HTTP sin cifrar. Incluye controles para QUIC, DNS y un MITM TLS experimental.

**Estado de esta entrega:** correcciones estáticas y pruebas aisladas de Kotlin superadas; **no es un APK validado**. En este entorno no hay Android SDK ni Gradle ejecutable, así que no se pudo hacer el build Android completo ni probar `VpnService.protect()` en el móvil donde apareció el error.

## Correcciones de esta revisión

- Las conexiones TCP salientes se crean a través de `SocketChannel.open()` y las UDP a través de `DatagramChannel.open()`. El descriptor nativo se crea antes de llamar a `VpnService.protect()`, y la llamada se hace antes de `connect()` en los sockets remotos.
- La conexión interna hacia `127.0.0.1:8888` en modo MITM no llama a `protect()`: es tráfico de loopback hacia el proxy local, no una conexión al servidor remoto.
- Si `protect()` devuelve `false`, NetLens registra si Android detecta que el permiso VPN no está activo/revocado o si rechaza el descriptor pese a que el permiso parece activo. No se omite la protección ni se sigue por una ruta potencialmente recursiva.
- MITM enlaza el socket servidor de forma síncrona antes de iniciar el lector TUN. Si el listener no puede arrancar, el inicio falla y la interfaz muestra el motivo.
- Los cambios de MITM durante una captura se rechazan: hay que detener y volver a iniciar para evitar que el estado visual difiera del proxy real.
- El proxy ya no corta voluntariamente una sesión después de 180 segundos ni aplica un timeout de 20 segundos a las lecturas de datos una vez terminado el handshake. Al detenerlo cierra las conexiones activas.
- La selección de apps no oculta errores. Si la app elegida no está instalada o el ámbito VPN no puede configurarse, el servicio no arranca con el ámbito predeterminado por accidente.
- Una petición HTTP completa sin `Host` (posible en HTTP/1.0) se reenvía con una nota en vez de quedarse retenida esperando el encabezado opcional.

## Prueba del filtro por dominio

1. Compila e instala el proyecto en un dispositivo de pruebas propio.
2. Mantén inicialmente **MITM TLS** y **Bloquear QUIC** desactivados.
3. En **Herramientas → Protección**, activa las reglas y añade una regla como `-example.com`.
4. Inicia la captura y prueba solo con una aplicación/dominio de laboratorio bajo tu control.
5. Comprueba que un SNI visible de `example.com` se bloquea, `notexample.com` no se bloquea por coincidencia parcial, y una regla más específica como `+safe.example.com` permite ese subdominio. Si dos reglas cubren el mismo dominio, la regla por paquete tiene prioridad sobre la regla global.
6. Desactiva la regla y verifica que el tráfico vuelve a pasar.

## Significado de los controles

- **Todas las apps / selector:** define qué aplicaciones entran al túnel. Si seleccionas una app concreta, las demás usan su conexión de red normal.
- **Bloquear QUIC:** descarta UDP al puerto 443. La app puede intentar TCP como alternativa, pero depende de su implementación.
- **MITM TLS:** termina y vuelve a establecer TLS para una app objetivo de laboratorio. Requiere confiar en una CA adecuada; no funciona con todas las apps y no elude certificate pinning.
- **Ocultar DNS:** solo oculta filas DNS en la vista en vivo; no cifra por sí mismo el DNS.
- **Pausar:** pausa la actualización de la vista, no la captura.
- **Protección:** reglas/listas de dominio. No es lo mismo que iniciar la VPN.

## Límites importantes

- **IPv6 no se inspecciona en esta versión.** La ruta de VPN configurada es IPv4; no debe tratarse como cortafuegos integral ni como garantía de ausencia de fugas.
- La implementación de TCP en espacio de usuario sigue siendo simplificada: no tiene una cola completa de retransmisión de los datos enviados al cliente ni un reensamblado fiable de segmentos fuera de orden en todos los casos.
- Se descartan paquetes IPv4 fragmentados y datagramas UDP demasiado grandes para el formato de salida actual. Los flujos que dependan de ellos pueden fallar.
- El bloqueo HTTPS normal trabaja por hostname/SNI visible, no por URL, ruta, cabeceras ni cuerpo cifrado. ECH o SNI ausente puede ocultar el nombre real.
- El búfer de clasificación se limita a 64 KiB; si se supera, se reenvía sin clasificar para evitar memoria ilimitada.
- DoH/DoT fallidos no tienen fallback silencioso al DNS en claro; el error se registra. La disponibilidad depende de la red y del proveedor configurado.
- MITM sigue siendo experimental; úsalo solo con apps/servidores propios o con autorización y datos ficticios.

## Comprobaciones locales ejecutadas

- Compilación JVM aislada de `Dpi.kt`, `Store.kt`, `Engine.kt` y `NetworkTools.kt` con stubs mínimos de Android.
- 2.000 entradas aleatorias al parser TLS sin excepción.
- Pruebas aisladas de reglas de dominio: dominio exacto, subdominios, frontera contra `notexample.com`, especificidad y alcance por paquete.
- Pruebas de control de `protect()` simulado como éxito y como fallo para TCP y UDP; se comprobó que el fallo no se silencia.
- Compilación aislada de `MitmProxy.kt` con stubs de Android y BouncyCastle.
- 16 comprobaciones estáticas de secuenciación, configuración y estado.
- Pruebas adicionales del relé TCP: ClientHello TLS permitido/bloqueado repartido entre segmentos, HTTP Host dividido/bloqueado, HTTP/1.0 completo sin Host, protocolo desconocido sin bloqueo y rama de bloqueo QUIC UDP/443.
- Prueba de precedencia que confirma que una regla específica por paquete gana frente a una regla global para el mismo dominio.
- Prueba de ciclo de vida MITM en JVM: `start()` devuelve con el puerto loopback enlazado y `stop()` cierra el listener y el socket de prueba.

La compilación del motor/MITM en Kotlin/JVM con stubs y las pruebas del relé no reproducen el kernel, el descriptor real de Android ni la red de un dispositivo. Estas pruebas no validan el comportamiento de Android real. Consulta `VALIDACION.md` antes de considerar este proyecto una build funcional.
