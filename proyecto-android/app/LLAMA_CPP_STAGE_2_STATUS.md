# llama.cpp — integración por GitHub Actions

La fuente completa de llama.cpp ya no se almacena en el proyecto.

`native/prepare_native_engines.sh` la descarga en tiempo de build, usa Android NDK/CMake y genera `libchatpro-llama.so`. La configuración sigue la ruta Android documentada por el proyecto oficial (`arm64-v8a`, `ANDROID_PLATFORM=android-28`, `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`).

El puente JNI existente de Chat Pro se conserva bajo `native/llama/llama_jni.cpp`.

El modelo GGUF continúa separado del APK: la app descarga/verifica el archivo y después se lo entrega al runtime.
