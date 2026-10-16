# v16.15 — salida de imagen sin copias Java

La ruta anterior era `stable-diffusion.cpp -> CPIMG1 -> ByteArray -> IntArray -> Bitmap -> PNG`.

Esta versión cambia únicamente la salida posterior a `generate_image`: el JNI escribe el `sd_image_t` directamente a PNG con zlib, fila a fila, y libera el buffer nativo después. Esto elimina las copias completas de imagen del lado Kotlin/Bitmap.

Los logs nativos ahora separan explícitamente:
- entrada/salida de `generate_image`;
- RSS nativo antes y después;
- dimensiones/canales/tamaño de `sd_image_t`;
- inicio/fin del PNG directo;
- liberación del buffer y contexto.

Importante: esta modificación no afirma que stable-diffusion.cpp pueda entregar una imagen parcial durante el muestreo. La API usada en esta versión devuelve el `sd_image_t` después de terminar la generación/decodificación. Por ello, la reducción de memoria actúa sobre la fase de salida y evita copias posteriores; si el pico de RSS sigue apareciendo antes de que `generate_image` devuelva, el siguiente paso debe ser estudiar el decoder/backend Vulkan dentro de stable-diffusion.cpp, no añadir otra copia de la imagen.
