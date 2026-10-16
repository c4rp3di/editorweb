# Chat Pro v19 — imagen + DeepSeek en paralelo

## Imagen

Base: v16.15 real, incluyendo su salida PNG nativa directa.

Corrección adicional basada en los logs de v15:

1. Se conserva `writePngStreaming()` nativo; no se vuelve a introducir Bitmap/ByteArray/IntArray.
2. El pico de RSS (~4.8 GB) aparece mientras `generate_image()` sigue ejecutándose después del último callback de muestreo.
3. Se activa VAE tiling de 256x256 con 50% de solape para reducir el pico durante el decode.
4. Se limita el presupuesto de memoria de trabajo Vulkan a 1.5 GiB mediante `max_vram`.
5. Se desactiva el prefetch para evitar reservas anticipadas.
6. La salida sigue siendo 512x512 PNG.

El `max_vram` es un presupuesto de memoria gestionada por el backend, no una garantía de límite físico absoluto; la propia documentación de stable-diffusion.cpp distingue las asignaciones externas del driver. La teselación VAE es la medida dirigida específicamente al pico de decodificación.

## DeepSeek

Se mantiene la corrección paralela:

- Jinja activado para el chat template del modelo.
- restauración del historial persistido en el contexto nativo.
- reconstrucción al abrir/cambiar conversación.
- reset al crear conversación nueva.
- restauración del historial recortado antes de regenerar.

## Alcance

No se ha compilado este ZIP porque el proyecto entregado no contiene `gradlew`. Se han realizado comprobaciones estructurales y de API sobre la referencia de stable-diffusion.cpp configurada por el proyecto.
