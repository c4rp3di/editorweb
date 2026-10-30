# AntiAds · laboratorio de técnicas contra anuncios de vídeo

App aparte (com.vigia.antiads) para probar técnicas sin tocar AdBlock Soberano+. Parte de una copia del Soberano+ y añade:
- Pantalla **Laboratorio**: interruptor por técnica, contadores, diagnóstico (copiar/compartir) y reglas por sitio (JSON importable).
- Proxy ampliado (módulo :vigia-mitm de ESTE proyecto): respuestas inventadas con CORS, script al inicio de la página con nonce en la CSP, reescritura de listas HLS.
- Su propia CA («AntiAds CA (local)»): hay que exportarla e instalarla aparte de la del Soberano+.

Solo puede haber una VPN activa: desactiva el Soberano+ antes de usar AntiAds.

## Qué probar
1. Ajustes › Modo Profundo (MitM): activar, exportar el CA, instalarlo, reactivar.
2. En el móvil, abre Chrome (no Brave) con la web.
3. En el Laboratorio, mira que sube «conexiones descifradas» y qué técnica actúa.
4. Si falla: Laboratorio › Compartir diagnóstico, y pásalo.

## Límites conocidos
- Las técnicas de red (VAST, scripts sustitutos, bloqueo clásico) actúan por URL en cualquier página; las de página (YouTube, saltador, CSS, HLS) por sitio, según reglas.json.
- Los anuncios metidos en el propio flujo de vídeo (SSAI) no se tocan, salvo HLS con marcas CUE-OUT/CUE-IN.
- La app de YouTube no acepta nuestra CA: solo YouTube web en Chrome.
- Sin probar en dispositivo: los scripts sustitutos y el de YouTube están escritos de memoria sobre cómo se comportan GPT, IMA y el reproductor.
