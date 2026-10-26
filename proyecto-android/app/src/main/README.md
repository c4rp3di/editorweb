# DevChat — chat de IA local con adjuntos (solo texto)

App independiente derivada de Chat Pro, **sin generación de imagen ni vídeo**. Identidad propia:
- `applicationId`: `com.ejemplo.devchat` (se instala junto a Chat Pro; datos separados)
- Nombre: **DevChat** · icono: bocadillo con `</>` sobre verde azulado · acento `#0F766E`

Para cambiarlas: `build.gradle` (applicationId), `res/values/strings.xml` (app_name), `res/values/colors.xml` +
`res/drawable/ic_launcher_foreground.xml` (icono), `res/values/themes.xml` y `res/drawable/bg_user.xml` (color).
El `namespace 'com.ejemplo.chat'` es el paquete interno del código y los nombres JNI: no lo cambies.

## Qué hace
- Chat 100 % local con modelos LiteRT-LM (Qwen3 0.6B, SmolLM2, Qwen 2.5 1.5B, **Gemma 4 E2B / E4B con visión**) y llama.cpp (DeepSeek-R1-Distill 1.5B).
- **＋ Adjuntar** (botón junto al campo de texto):
  - **Archivos de texto** (.log, .txt, .json, .kt, .xml, .md, .html…): se leen y se envían al modelo como bloques de código.
    Los logs se compactan (líneas repetidas ×N, líneas enormes acortadas) y, si no caben, se conserva el 30 % inicial y el 70 % final.
  - **Imágenes / capturas**: OCR en el dispositivo (ML Kit, funciona sin conexión) y, con Gemma 4, el modelo además **ve** la imagen.
    Con modelos sin visión solo recibe el texto del OCR.
- Streaming, botón **■** para detener, razonamiento `<think>` oculto, copiar y regenerar.
- 🐞 Depuración: `debug.log` + `native.log` (crashes nativos con pila).

## Límites de adjuntos (caracteres)
llama.cpp (contexto 8192): 10 000 · Gemma con visión: 10 000 · resto de modelos LiteRT: 5 000. Solo el último mensaje con adjuntos conserva
su contenido en el historial; los anteriores se sustituyen por una nota para no llenar el contexto.

## Compilación
`native/prepare_native_engines.sh` compila **solo llama.cpp** (CPU, arm64, `-march=armv8.6-a+fp16+dotprod+i8mm`). Ya no necesita Vulkan,
glslc ni SPIR-V: puedes quitar esos pasos de tu workflow de GitHub Actions. El APK exige un móvil con i8mm
(Dimensity 9000+, Snapdragon 8 Gen 1+...); si ves SIGILL, quita la línea `GGML_CPU_ARM_ARCH` de `native/llama/CMakeLists.txt`.
Nueva dependencia: `com.google.mlkit:text-recognition:16.0.1` (modelo OCR incluido en el APK, ~4 MB).

## Notas para el editor (EditorWeb)
- **Carpeta native/**: el editor v10 publica `native/…` en `app/src/main/native/…` (versiones anteriores, en `app/native/…`).
  `build.gradle` detecta cuál existe; si falta, la build falla con un mensaje explícito. Hay que publicar los 3 archivos:
  `native/prepare_native_engines.sh`, `native/llama/CMakeLists.txt`, `native/llama/llama_jni.cpp`.
- **Icono**: `res/mipmap-xxxhdpi/ic_launcher.png` (192×192). El editor lo importa como icono del proyecto y genera los 5 tamaños
  (normal + redondo); puedes cambiarlo desde el propio editor. No incluyo icono adaptable en XML para no taparlo.
