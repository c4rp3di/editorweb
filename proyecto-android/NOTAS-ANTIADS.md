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

## Hallazgos de la primera prueba (es.pornhub.com en Chrome)
- Sus anuncios NO usan VAST/IMA/GPT: el reproductor pide los vídeos (.mp4) y las imágenes a adtng.com / trafficjunky.net y envía eventos a etahub.com. Por eso vast/stubs no actuaban.
- Se añadió la técnica «juguete»: esos .mp4 se sustituyen por un MP4 válido de 0,3 s (assets/anuncio_vacio.mp4).

## Segunda prueba (es.pornhub.com)
- Los scripts de anuncios (embeddedads, popunder, gtm) quedan vaciados desde el origen: no llegan a pedirse los .mp4 (por eso «juguete» = 0).
- `media.trafficjunky.net/delivery/js/abp/js1.js` parece un detector de bloqueadores (abp). Hipótesis: al vaciarlo, el sitio detecta bloqueo y no reproduce. Se añade la clave `permitir` en reglas.json: esa URL ya no se bloquea.
- Los eventos de etahub.com se repetían (mismo ndate): la respuesta vacía no llevaba cabeceras CORS y el emisor la tomaba por fallo. Ahora el bloqueo clásico responde con CORS y atiende el preflight.

## Tercera prueba: el pre-roll seguía saliendo
- El diagnóstico solo anotaba lo que las técnicas modificaban; no se veía qué peticiones pasaban sin tocar. Nuevo interruptor del Laboratorio: «Registrar vídeo y audio (diagnóstico)», que anota (req) las peticiones de vídeo/audio/HLS/archivos grandes con código, tipo y tamaño.
- El túnel solo enruta IPv4. Con el Modo Profundo activo, las consultas DNS de tipo AAAA se contestan «sin datos» (FiltroTrafico.filtrarAaaa) para que las apps usen IPv4 y pasen por el proxy.
