# Chat Pro — etapa nativa llama.cpp

## Estado de esta entrega

Esta entrega incorpora la capa segura de preparación del modelo GGUF y el contrato JNI, pero **no declara llama.cpp nativo funcional todavía**.

La razón es técnica: la biblioteca Android oficial de llama.cpp se construye a partir de su árbol C++/CMake y no se ha incluido ningún `.so` precompilado no verificado. El proyecto conserva LiteRT como motor de texto operativo mientras esta segunda mitad se incorpora y se prueba en el Xiaomi 13T Pro.

## Modelo objetivo

`DeepSeek-R1-Distill-Qwen-1.5B Q4_K_M`

Archivo:

`DeepSeek-R1-Distill-Qwen-1.5B.Q4_K_M.gguf`

Directorio local:

`filesDir/llama/models/`

El gestor comprueba dos cosas antes de activar el modelo:

1. Cabecera `GGUF`.
2. SHA-256 exacto:

`41aa31689f2cbdcc5172e370db2ab7a10e17a9427520602437bd16d8d127d105`

El archivo definitivo no se presenta como listo hasta que el `.part` pasa ambas comprobaciones.

## Contrato nativo

`ia/llama/LlamaCppNative.kt` reserva la biblioteca:

`chatpro-llama`

y las operaciones mínimas:

- obtener versión
- cargar GGUF
- generar texto
- liberar modelo

`NativeLlamaCppTextEngine` implementa el contrato `TextEngine`, pero no se invoca desde `ChatActivity` en esta fase.

## Próxima etapa

Con el árbol real de llama.cpp disponible, la compilación Android debe seguir la configuración documentada oficialmente para `arm64-v8a`, con `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF` y `LLAMA_OPENSSL=OFF`. La documentación también recomienda comenzar con un contexto moderado, por ejemplo 4096, para evitar picos innecesarios de memoria.

La secuencia sigue siendo obligatoria:

`compilar → CPU → Vulkan → medir → integrar en ChatActivity`
