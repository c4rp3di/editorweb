# Chat Pro — FINAL

## Generación de imágenes local

La aplicación integra el pipeline real de FLUX.2 [klein] 4B mediante LiteRT GPU.

**Nunca descarga modelos automáticamente.** El modelo solo se descarga después de que el usuario pulse Descargar y confirme.

### Requisito FLUX
El paquete runtime debe contener todos los grafos, pesos externos y assets de preparación exigidos por la revisión seleccionada. La app no habilita Generar mientras falte alguno.

### Importante
Esta entrega integra el pipeline de generación y el gestor de modelos. El rendimiento y compatibilidad concretos del Xiaomi 13T Pro deben verificarse en el dispositivo físico; el ejemplo oficial fue verificado en Pixel 8a/Mali y no implica que todos los teléfonos Android sean compatibles.

Fuente técnica: Google AI Edge LiteRT FLUX.2-klein sample.
