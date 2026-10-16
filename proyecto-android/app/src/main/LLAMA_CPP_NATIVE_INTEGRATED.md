# Integración nativa de llama.cpp — build-time

El árbol completo del motor se obtiene en GitHub Actions durante `preBuild` y no se publica archivo por archivo junto al proyecto de Chat Pro.

`native/llama/CMakeLists.txt` compila un puente JNI `chatpro-llama` y enlaza las bibliotecas de llama.cpp como parte de un árbol CMake aislado.

El flujo Android se limita a `arm64-v8a` y usa CMake/NDK. El runtime recibe la ruta a un GGUF que ya haya sido descargado y verificado por Chat Pro.
