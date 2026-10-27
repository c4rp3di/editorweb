# NetLens (laboratorio didáctico)

VPN local + relay TCP/UDP + DPI ligero + auditoría de permisos + **MITM TLS opcional (fase 2)**.
Todo en local, sin servidor.

## Fase 1
DNS, SNI, HTTP en claro, atribución por app, permisos.

## Fase 2 (MITM TLS)
1. Arranca NetLens una vez para que se genere la CA local.
2. Pulsa **Exportar CA**. El certificado `netlens-ca.crt` se guarda en Descargas.
3. Instálalo: Ajustes → Seguridad → Cifrado y credenciales → Instalar certificado → **Certificado de CA** → selecciona el archivo.
4. Comprueba la huella SHA-256 que muestra NetLens tras exportar.
5. Activa **MITM TLS** en NetLens.
6. Solo funcionará con apps que acepten CAs de usuario (por ejemplo, tus propias apps compiladas en debug con `network_security_config.xml` que incluya `<certificates src="user"/>` dentro de `<debug-overrides>`).
7. Las apps con certificate pinning rechazarán el certificado: comportamiento correcto. No se salta pinning.

## Alcance legal
Solo tu dispositivo, tus apps, tu tráfico. Interceptar tráfico de terceros o de redes ajenas es ilegal.

## Limitaciones conocidas
- Solo se hace MITM de TCP:443. Otros puertos van directos.
- QUIC (UDP:443) no se descifra. Usa «Bloquear QUIC» para forzar TCP+TLS.
- TLS 1.3 con ECH no se puede descifrar (el SNI va cifrado).
- Apps con certificate pinning fallarán el handshake.