# Chat Pro — paquete mínimo para Actions

Este ZIP está pensado para el editor que publica los archivos uno a uno.

Incluye el proyecto Android de Chat Pro y solamente las piezas nativas pequeñas que deben publicarse:

- puente JNI de llama.cpp
- puente JNI de stable-diffusion.cpp
- CMake aislado para cada motor
- script de preparación que descarga y compila los repositorios oficiales durante `preBuild`
- catálogo base de vídeo (Wan2.1 T2V 1.3B)
- puente Kotlin para imagen/vídeo
- documentación de build

No incluye:

- el árbol de llama.cpp
- el árbol de stable-diffusion.cpp
- modelos GGUF/safetensors
- Gradle wrappers adicionales
- medios de ejemplo
- binarios `.so` preconstruidos


### Blindaje de toolchains
La preparación nativa detecta la versión Vulkan del NDK y descarga el conjunto completo de `KhronosGroup/Vulkan-Headers` con el tag exacto correspondiente. Esto evita mezclar `vulkan.hpp` de una versión nueva con `vulkan_core.h` del NDK. También se fija `stable-diffusion.cpp` a un commit concreto para que Actions no cambie de código silenciosamente.
