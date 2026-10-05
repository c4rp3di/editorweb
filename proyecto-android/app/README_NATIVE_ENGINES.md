# Chat Pro — motores nativos (build-time)

Esta versión NO guarda los árboles completos de llama.cpp ni stable-diffusion.cpp dentro del proyecto Android.

Durante `preBuild`, Gradle ejecuta `native/prepare_native_engines.sh`. Ese script, en el runner de GitHub Actions, descarga los repositorios oficiales, los compila para `arm64-v8a` con Android NDK/CMake y copia solamente estas bibliotecas generadas a `app/native/jniLibs/arm64-v8a/`:

- `libchatpro-llama.so`
- `libchatpro-diffusion.so`

Los pesos de modelos (`.gguf`, `.safetensors`, etc.) nunca se empaquetan en el APK desde este mecanismo.

## Referencias de build

- llama.cpp: `v0.6.0` por defecto; se puede cambiar con `LLAMA_CPP_REF`.
- stable-diffusion.cpp: `master` por defecto; se puede cambiar con `STABLE_DIFFUSION_CPP_REF`.
- NDK: `29.0.13113456`.
- CMake: `3.31.6`.
- ABI: `arm64-v8a`.
- plataforma Android nativa: API 28.
- Vulkan habilitado para stable-diffusion.cpp.

## Descarga de modelos

La aplicación mantiene separadas las bibliotecas nativas de los pesos. Los modelos se almacenarán en el almacenamiento privado de la aplicación y pasarán por el flujo **Descargar → Verificar → Usar** antes de activarse.

## Desarrollo local

Para compilar solamente la parte Kotlin sin descargar/compilar motores nativos se puede usar:

`CHATPRO_SKIP_NATIVE=1`

Para el build de GitHub Actions no se debe activar esa variable.
