# Chat Pro — plan de inferencia nativa

## 1. Texto
Estado actual: capa de modelo GGUF + verificación SHA-256 + contrato JNI preparados; llama.cpp nativo todavía no se marca como disponible.

Objetivo: sustituir progresivamente el motor LiteRT por una ruta basada en llama.cpp.

Modelo objetivo de la primera prueba: `DeepSeek-R1-Distill-Qwen-1.5B Q4_K_M` en GGUF.

Secuencia obligatoria antes de integrarlo en la UI:
1. Incorporar el árbol real de `llama.cpp` y compilarlo para `arm64-v8a`.
2. Ejecutar prueba CPU con el GGUF.
3. Ejecutar prueba Vulkan y comprobar que se detecta el Immortalis-G715.
4. Medir tokens/s, memoria y estabilidad.
5. Solo si ambas rutas son estables, crear JNI/Kotlin `TextEngine`.

## 2. Imagen
La implementación FLUX de v14.1 se ha retirado porque no llegó a generar una imagen válida en el dispositivo objetivo.

Backend previsto: `stable-diffusion.cpp` con Vulkan.

Primera prueba: una configuración pequeña y reproducible de imagen; no se conectará a `ChatActivity` hasta tener generación real verificada.

## 3. Vídeo
Backend previsto: Wan2.1 T2V 1.3B con Vulkan.

Se mantendrá como módulo separado del motor de texto para poder liberar memoria antes de generar vídeo.

## 4. Regla de modelos
Todos los motores nuevos deben seguir:

`Descargar → Verificar → Usar`

Nunca se descarga un modelo automáticamente al arrancar, seleccionar o generar.
