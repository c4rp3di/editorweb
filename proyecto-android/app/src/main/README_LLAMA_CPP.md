# Chat Pro — llama.cpp: entrega preparada

Esta entrega es deliberadamente intermedia: deja preparada la gestión real del GGUF y el contrato JNI, pero **no finge tener llama.cpp nativo dentro del APK**.

## Qué se ha añadido

- `ia/llama/LlamaCppModel.kt`
- `ia/llama/LlamaCppModelManager.kt`
- `ia/llama/LlamaCppNative.kt`
- `ia/llama/TextEngine.kt`
- `LLAMA_CPP_NATIVE_STAGE.md`

## Modelo

`DeepSeek-R1-Distill-Qwen-1.5B Q4_K_M`

SHA-256 esperado:

`41aa31689f2cbdcc5172e370db2ab7a10e17a9427520602437bd16d8d127d105`

La fuente del modelo indica un tamaño de 1.12 GB y proporciona ese SHA-256. La comprobación que hace la app es sobre el archivo completo descargado, además de comprobar la cabecera `GGUF`.

## Prueba que toca ahora

1. Sustituir tu proyecto actual por este ZIP.
2. Compilar `assembleDebug` como en la versión anterior.
3. Instalar y abrir la app.
4. No hace falta descargar el DeepSeek todavía.

El objetivo de este paso es únicamente confirmar que la nueva capa queda integrada sin romper la aplicación existente.

## Bloqueo conocido de la siguiente etapa

Para que `LlamaCppNative` pase de contrato a inferencia real hace falta incluir el árbol C++/CMake de llama.cpp y producir `libchatpro-llama.so` para `arm64-v8a`. La documentación oficial de llama.cpp describe precisamente esa ruta de compilación Android mediante NDK/CMake. No se incluye un `.so` externo no verificado.
