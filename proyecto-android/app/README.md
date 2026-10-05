# Chat Pro — base de arquitectura v15

Esta versión parte de v14.1 y retira la integración FLUX experimental. El objetivo es evitar que la UI dependa de un pipeline de inferencia que no llegó a validarse en el Xiaomi 13T Pro.

## Política de modelos
- Ningún modelo se descarga al arrancar.
- Ningún modelo se descarga al seleccionarlo.
- Ningún modelo se descarga al pulsar generar.
- La descarga de modelos de texto sigue siendo explícita y reanudable.
- El backend de imagen queda catalogado pero no se marca como utilizable hasta verificar la implementación nativa.

## Nueva arquitectura
- Texto: se mantiene temporalmente el motor LiteRT existente mientras se prepara el banco de prueba de llama.cpp + DeepSeek-R1-Distill-Qwen-1.5B Q4_K_M.
- Imagen: nueva frontera prevista `stable-diffusion.cpp + Vulkan`; no se reutiliza FLUX.
- Vídeo: preparado como módulo independiente para Wan2.1 T2V 1.3B + Vulkan.
- Archivos: `CreatedFilesManager` almacena imágenes, vídeos y otros archivos en `filesDir/generadas/` con índice JSON y relación opcional con conversación/prompt/modelo.

## Mis archivos
La barra lateral incluye `📁 Mis archivos`. Permite filtrar imágenes/vídeos/otros, abrir, compartir mediante `FileProvider` y borrar registros/archivos locales.

## Estado de inferencia
Todavía no se declara funcional ninguna nueva ruta nativa hasta pasar una prueba real en ARM64/Vulkan. La documentación actual de llama.cpp mantiene soporte de Android ARM64 y compilación mediante Android NDK; su backend Vulkan se puede seleccionar como backend de ggml. stable-diffusion.cpp documenta soporte de Android y Vulkan, además de Wan2.1/Wan2.2.

## Etapa llama.cpp
Se añade `ia/llama/` con el catálogo del DeepSeek-R1-Distill-Qwen-1.5B Q4_K_M, descarga manual reanudable, verificación GGUF + SHA-256 y un contrato JNI para el futuro runtime `chatpro-llama`.

La ruta nativa queda aislada de `MotorIA` hasta incluir y validar la biblioteca C++ real. El funcionamiento actual de texto no se sustituye ni se rompe en esta etapa.
