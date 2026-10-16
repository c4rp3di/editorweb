# Imagen y vídeo — base nativa

Chat Pro incorpora ahora el esqueleto de build para `stable-diffusion.cpp` sin guardar su código fuente en el repositorio.

## Imagen

- Backend: Vulkan.
- JNI: `ia/DiffusionNative.kt` + `native/diffusion/diffusion_jni.cpp`.
- Salida nativa provisional: contenedor interno `CPIMG1` con los píxeles de la primera imagen.
- La capa Android posterior puede convertir `CPIMG1` a PNG/JPG y registrar el resultado en **Mis archivos**.

## Vídeo

- Backend: Vulkan.
- Primer objetivo: Wan2.1 T2V 1.3B.
- JNI: misma biblioteca `chatpro-diffusion`.
- Salida nativa provisional: contenedor interno `CPVID1` con frames RGBA y metadatos de anchura, altura, fps y número de frames.
- La siguiente capa Android debe convertir `CPVID1` a MP4 con `MediaCodec`/`MediaMuxer` y registrar el fichero en **Mis archivos**.

No se incluyen pesos ni medios de ejemplo.
