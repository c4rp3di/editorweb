# Chat Pro — llama.cpp Stage 2 status

## Included in this package

- The DeepSeek-R1-Distill-Qwen-1.5B Q4_K_M GGUF is now part of the visible model selector.
- Download is manual only.
- Downloads use `LlamaCppModelManager` with restart/resume support.
- The `.part` file is checked for the GGUF magic before activation.
- SHA-256 is checked before the final model file is activated.
- The final GGUF is stored under `filesDir/llama/models/`.
- Selecting the verified GGUF is persisted separately from the existing LiteRT model selection.
- The UI explicitly reports when the GGUF is verified but the native llama.cpp runtime is not present.

## Not falsely claimed as complete

The actual llama.cpp C/C++ runtime is **not** bundled in this package. The JNI contract exists, but no fake or placeholder native implementation is used as if it were llama.cpp.

The official llama.cpp Android documentation currently describes the supported path as an Android NDK/CMake native build and an Android binding that loads GGUF files from app-private storage. The next native step must therefore add a real llama.cpp source tree/build to the APK, then validate CPU inference on arm64-v8a before attempting Vulkan.
