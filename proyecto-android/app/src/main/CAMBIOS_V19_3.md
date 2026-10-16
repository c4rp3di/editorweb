# v19.3 — cambios (sin compilar: revisa el log de GitHub Actions)

## Generación de imagen/vídeo
- Corre en un proceso aparte (`:gen`, `GenService` + `GenClient`). Botón ■ = matar ese proceso. Un abort nativo / device lost ya no cierra la app.
- Porcentaje real (preparación 0-5 % · muestreo 5-90 % · decodificación 90-99 %) con tiempo y estimación.
- Flash attention ACTIVADO otra vez (desactivado fue ~10x más lento). VAE en CPU, T5 (vídeo) en CPU.
- `cfg=1` si no hay prompt negativo (evita la segunda pasada).
- Log de stable-diffusion.cpp registrado (`SD INFO/WARN/ERROR` en native.log).

## DeepSeek (llama.cpp)
- Streaming por trozos (callback JNI, UTF-8 seguro) y botón ■ para detener.
- Límite de 2048 tokens (antes 256) y contexto 8192 (antes 4096).
- El historial guarda solo la RESPUESTA (sin `<think>`); un turno que se corta pensando NO entra en el historial.
  Si el KV tenía razonamiento, el siguiente turno reconstruye el contexto con el historial limpio (`reconstruido=si` en el log).
- En pantalla solo se ve la respuesta; mientras piensa muestra "💭 Pensando… (N palabras)".
- Muestreo: temperatura 0.6, top_p 0.95, penalización de repetición 1.05.
- Log nativo de llama filtrado a avisos/errores (adiós a las cientos de líneas `llama_graph_n_input_tensors`).
- CPU: `-march=armv8.6-a+fp16+dotprod+i8mm` (ggml-cpu). Debe aparecer `DOTPROD = 1 | MATMUL_INT8 = 1` en `[LLAMA] CPU/ruta`.
  OJO: el APK solo funcionará en SoC con i8mm (Dimensity 9000+, Snapdragon 8 Gen 1+...). Si ves SIGILL, quita esa línea de los dos CMakeLists.

## Qué mirar en el próximo log
- `[LLAMA] CPU/ruta:` con DOTPROD/MATMUL_INT8.
- `[LLAMA] salida: ... trozos/s` y `nativo: ... parada=fin_de_secuencia ... think=cerrado ... reconstruido=...`.
- `[GEN] t=..s · NN % · muestreo paso x/y` y `gen RSS ... MB`.
