# Chat Pro — reproducibilidad de motores nativos

Este paquete evita dependencias nativas grandes dentro del editor/repositorio. Actions las obtiene y compila.

## Puntos que quedan fijados o comprobados

1. **ABI**: `arm64-v8a`.
2. **Android API**: 28.
3. **NDK**: `29.0.13113456` por defecto.
4. **CMake**: `3.31.6` por defecto.
5. **llama.cpp**: `v0.6.0`.
6. **stable-diffusion.cpp**: commit `3f8527a` por defecto; no `master`. El script resuelve el SHA corto localmente en un partial clone y hace checkout exacto; no intenta usar el SHA corto como ref remoto.
7. **SPIR-V Headers**: se usa el paquete CMake disponible o se genera un prefijo local desde Khronos si el runner no lo aporta.
8. **Vulkan-Headers**: se lee `VK_HEADER_VERSION` del NDK, se calcula `v<major>.<minor>.<patch>`, se exige ese tag exacto en Khronos y se usa el directorio `include` completo.
9. **Vulkan C/C++ headers**: se valida que `vulkan.hpp` y `vulkan_core.h` procedan del mismo conjunto y que `VK_HEADER_VERSION` coincida con el NDK.
10. **No se guardan fuentes de terceros ni `.so` precompiladas en el ZIP**.

## Motivo

El fallo de Action #153 demostró que tener `vulkan.hpp` no es suficiente: un `vulkan.hpp` más nuevo que el `vulkan_core.h` del NDK produce tipos Vulkan inexistentes en el header C. El script ahora falla explícitamente si no puede obtener una pareja exacta.
