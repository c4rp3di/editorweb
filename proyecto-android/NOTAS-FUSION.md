# AdBlock Soberano Lab 2.1.0 — fusión de AdBlock Soberano+ y NetLens 2.6.0

Base: tu AdBlock Soberano+ 2.0 (el que ya compila). App NUEVA e independiente: applicationId `com.vigia.adblocklab`, nombre «AdBlock Soberano Lab», CA propia («AdBlock Soberano Lab CA»). Convive con AdBlock Soberano+ y NetLens, pero solo una VPN puede estar activa a la vez; instala su CA aparte.

## Qué hay de nuevo
- Ajustes › **Modo de protección**: Ligero (solo DNS, como siempre) o Profundo (todo el IPv4: SNI + QUIC). El botón «SNI experimental» antiguo desaparece; es el Profundo.
- Ajustes › **Anti-evasión** (de NetLens 2.4): anti-DoH (dominios + puerto 853), anti-ECH (consultas HTTPS/SVCB vacías), neutralizar IPv6. Anti-DoH y anti-ECH valen en ambos modos; 853 e IPv6 solo en Profundo. La primera vez que pasas a Profundo se activan las tres.
- Ajustes › **Descifrado HTTPS (MitM)** (es el antiguo «Modo Profundo (MitM)») + **reglas de ruta propias** `dominio.com/ruta/*` (de NetLens 2.6).
- **Laboratorio** (pantalla nueva): resumen por app, actividad reciente, detalle con permisos sensibles y destinos que están en tus listas, revisión de permisos, exportar CSV, guía. Todo en memoria. Con el registro apagado el túnel sigue tan ligero como antes.

## Cambios en :vigia-core (todos opcionales, por defecto apagados)
ConfigVpn +3 campos (bloquearDot, bloquearEch, neutralizarIpv6) · FiltroAntiDoh (nuevo) · DnsParser.esTipoEch · Reenviador (853 TCP/UDP, ECH en UDP/53) · NucleoVpnService (ECH en el DNS falso, rutas IPv6).

## Lo que NO se ha portado, a propósito
- Consulta de IP (ip-api.com): envía cada IP a un tercero por HTTP sin cifrar.
- Historial de sesiones en disco y log de depuración: el CSV cubre el caso y no queda nada guardado.
- Categorías de rastreadores de NetLens: se usan tus listas de bloqueo.
- No se pide QUERY_ALL_PACKAGES: el Laboratorio solo ve apps con icono de lanzador.

## Estado
NO compilado (no hay SDK aquí). Revisado a mano, con una segunda revisión independiente: sin errores de compilación detectados. Primera compilación = posibles fallos menores.
Prueba mínima: 1) Ligero: todo igual que antes. 2) Profundo + anti-evasión: webs y apps cargan. 3) Laboratorio: activar registro, abrir una app, ver filas. 4) Si algo falla en Profundo, desmarca primero «Neutralizar IPv6».
