# DevChat v1 (a partir de Chat Pro v19.3)

Quitado: stable-diffusion.cpp, Vulkan, generación de imagen/vídeo, proceso `:gen`, "Mis archivos", FileProvider y catálogos de pesos de imagen.
Movido: el registro nativo (stdout/stderr + señales) pasa de `libchatpro-diffusion.so` a `libchatpro-llama.so`.
Nuevo: adjuntar archivos de texto e imágenes con OCR (ML Kit) + visión Gemma 4; botón ■ también para LiteRT; streaming en LiteRT.
Corregido: `toString` del JNI de llama usaba "modified UTF-8" (los emojis de logs/capturas llegaban rotos al tokenizador).
Corregido: "Regenerar" enviaba la pregunta dos veces al modelo (historial restaurado incluía ya la pregunta).
Heredado de v19.3: streaming llama.cpp, parada, historial sin `<think>`, 2048 tokens / contexto 8192, flags ARM dotprod+i8mm.
