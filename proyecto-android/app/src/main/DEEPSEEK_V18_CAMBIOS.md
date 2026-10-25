# Chat Pro v17 — DeepSeek Jinja + persistencia de conversación

## Objetivo

Esta entrega corrige y aísla dos problemas del runtime nativo de DeepSeek-R1-Distill-Qwen-1.5B sin modificar la instrumentación de Stable Diffusion de la entrega anterior.

## Cambios realizados

### 1. Plantilla de chat del GGUF

`native/llama/llama_jni.cpp` pasa `use_jinja=true` a `common_chat_format_single()`.

Esto hace que llama.cpp utilice la plantilla de chat incluida en los metadatos del modelo. La plantilla oficial de DeepSeek-R1-Distill-Qwen-1.5B elimina el contenido anterior a `</think>` al reutilizar respuestas del assistant y abre la siguiente generación con `<｜Assistant｜><think>`. Esto evita reutilizar el razonamiento interno anterior como si fuera texto normal de conversación.

### 2. Restauración del historial persistido

Se añadió `nativeRestoreHistory()`.

La aplicación puede reconstruir el contexto nativo desde el historial JSON de la conversación:

- reinicia el `llama_context`;
- aplica la plantilla Jinja a todo el historial;
- tokeniza el historial completo;
- lo procesa en llama.cpp;
- restaura `history` y `current_position`.

Por tanto, el JSON de `conversaciones/` pasa a ser la fuente persistente y el KV/contexto nativo se reconstruye al cargar la conversación.

### 3. Aislamiento entre conversaciones

Al cambiar de conversación mientras DeepSeek está activo se reconstruye el contexto usando únicamente la conversación seleccionada.

`Nueva conversación` restaura un historial vacío y, por tanto, reinicia el contexto nativo.

### 4. Regenerar

Antes de regenerar la última respuesta de DeepSeek se reconstruye el contexto sin esa respuesta. Esto evita que la respuesta antigua permanezca en el KV cache aunque se haya eliminado de la interfaz.

## Imagen

No se ha modificado la lógica de Stable Diffusion de la entrega de diagnóstico anterior. Se conserva la instrumentación para investigar el salto de RSS en `fase=3 paso=140/140`.

## Importante: compilación

Esta entrega es **fuente modificada**. El entorno de trabajo no contiene `gradlew`, por lo que aquí no se ha ejecutado `assembleDebug` y no se afirma que el APK compile.

La compilación y prueba en el Xiaomi deben hacerse antes de introducir cambios adicionales.

## Prueba DeepSeek propuesta

Con el mismo GGUF y dispositivo:

1. Borrar el registro.
2. Abrir una conversación nueva.
3. Preguntar: `Créame un mensaje de 80 palabras sobre un gato`.
4. Preguntar: `Ahora habla de otra cosa`.
5. Preguntar: `Solamente responde hola.`.
6. Cerrar y volver a abrir la conversación.
7. Hacer una cuarta pregunta relacionada con el historial.
8. Crear una nueva conversación y comprobar que no arrastra el contexto anterior.
9. Regenerar una respuesta y comprobar que no se acumula la respuesta antigua en el contexto.

## Líneas de log nuevas/relevantes

Buscar:

- `historial restaurado`
- `historial_restaurado=`
- `plantilla=si`
- `formateado_final=`
- `pos=`

El objetivo es comprobar que el historial persistido se reconstruye y que las respuestas nuevas dejan de estar condicionadas por el bloque `<think>` anterior.
