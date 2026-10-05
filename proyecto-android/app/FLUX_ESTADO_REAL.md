# Estado real de la generación de imágenes

## Importante
Este proyecto **no descarga ningún modelo automáticamente**.

El catálogo y el gestor de descargas están preparados para que el usuario inicie manualmente la descarga. El runtime de FLUX.2 Klein requiere el paquete completo y su contrato de archivos; no basta con los 21 `.tflite` del repositorio LiteRT básico.

## Por qué no se marca como "generación funcional" todavía
La ejecución completa requiere:
- resolver los sidecars `weights/*.bin`;
- enumerar `SignatureDef` y enlazar pesos externos;
- implementar el tokenizer Qwen2 byte-level BPE;
- cargar `qwen_embed_fp16.bin`;
- construir máscara causal/padding y RoPE;
- ejecutar las 3 etapas `ke_enc*`;
- ejecutar las 8 etapas `kc_*` durante 4 pasos;
- aplicar el scheduler y las permutaciones de latentes;
- ejecutar `kv_vae.tflite` y convertir el tensor a Bitmap.

La documentación oficial confirma que todo eso forma parte del pipeline y que ejecutar un único `.tflite` no produce una imagen completa.

Por tanto, **no se debe afirmar que este ZIP ya genera imágenes** hasta que esa capa de inferencia esté implementada y probada en un dispositivo Android real.
