# Chat Pro — integración FLUX.2 [klein] 4B

Proyecto Android con interfaz de chat local, modelos de texto descargables manualmente y modo de generación de imágenes local.

## Política de descarga
- Ningún modelo se descarga al arrancar.
- Ningún modelo se descarga al seleccionarlo.
- Ningún modelo se descarga al pulsar «Generar».
- La red solo se usa para una descarga explícitamente iniciada por el usuario.
- Las descargas grandes usan `.part` + `Range` para poder reanudarse.

## FLUX.2 [klein] 4B
El flujo de texto→imagen usa los grafos T2I `ke_enc0..2`, `kc_prep`, `kc_double0..1`, `kc_single0..3`, `kc_final` y `kv_vae`, más el tokenizer Qwen y su tabla de embeddings fp16.

El proyecto prepara en el dispositivo:
- plantilla de conversación Qwen3;
- tokenización BPE a partir de `qwen_vocab.txt`, `qwen_merges.txt` y `qwen_special.txt`;
- lookup memory-mapped de `qwen_embed_fp16.bin`;
- máscara causal expandida por cabeza;
- RoPE de Qwen y RoPE 4D de FLUX.2;
- schedule FLUX.2 de 4 pasos y actualización Euler/flow matching;
- ruido inicial y transformación packed-latent→VAE.

Los valores aprendidos que no deben inventarse (embeddings de tiempo proyectados y estadísticas BatchNorm del VAE) se leen de los artefactos `host/` del mismo paquete coherente de runtime. El generador se niega a ejecutarse cuando faltan.

## Estado de verificación
La parte FLUX pura de Kotlin compila con stubs del SDK para comprobar sintaxis. El entorno de trabajo actual no contiene Gradle/Android SDK, por lo que aquí no se puede afirmar una compilación Android final ni una inferencia en el Xiaomi. La validación definitiva es: importar el proyecto en el editor Android, compilar, descargar el paquete manualmente, activar FLUX y ejecutar un prompt.

## Estructura
`app/src/main/...` es la estructura Gradle estándar.

## Modos de motor de imagen (v14)
El botón «⚙ Motor de imagen» cicla entre tres modos:
- **CPU puro**: todo en XNNPACK. `kc_prep` llega a ~6,7 GB al compilar y en Xiaomi/HyperOS el sistema cierra la app (LOW_MEMORY). Solo para diagnóstico.
- **CPU + kc en GPU (recomendado, por defecto)**: `ke_enc*` y `kc_double/kc_single` en CPU; `kc_prep`/`kc_final` (~185 MB) en GPU. Compatible con el límite de ~1,5 GB de GpuMemory de HyperOS.
- **GPU**: todo en GPU. `ke_enc0` pisa ~3,5 GB de GpuMemory y HyperOS cierra la app por encima de ~1,5 GB.
