# DocReaderEditor

App Android nativa y offline (Kotlin 1.9.24, AGP 8.5.0, Gradle 8.7, JDK 17, SDK 34, KSP 1.9.24-1.0.20, Hilt 2.51.1, Room 2.6.1).

## Compilar
1. Abrir la carpeta en Android Studio (Koala o posterior) con JDK 17, o por terminal:
2. Si falta `gradle/wrapper/gradle-wrapper.jar`: `gradle wrapper --gradle-version 8.7`
3. `./gradlew assembleDebug`

## Funcionalidad (v1)
| Formato | Ver | Editar | Notas |
|---|---|---|---|
| TXT, MD, CSV/TSV, JSON, XML | ✅ | ✅ texto plano | MD con vista previa (Markwon) |
| HTML | ✅ texto | ✅ texto plano | al guardar se pierden las etiquetas |
| DOCX | ✅ con formato | ✅ editor tipo Word | ver «Fase 1: editor Word» |
| XLSX | ✅ con fórmulas calculadas | ✅ editor tipo Excel | ver «Fase 2: editor Excel» |
| PDF | ✅ PdfRenderer | ❌ | OCR ML Kit (máx. 30 págs) para búsqueda |

Biblioteca con búsqueda por nombre y por texto extraído (Room). Acceso a archivos solo con SAF, sin permisos.

## Pendiente respecto al plan
Edición PDF (androidx.pdf Ink, alpha), ajustes, anotaciones, tests.
PDFs con contraseña no soportados (PdfRenderer no admite clave antes de API 35).

## Edición DOCX/XLSX (sin Apache POI)
Se edita el paquete OOXML directamente (ZIP + XML), copiando intactas el resto de entradas.
- DOCX: los párrafos con imágenes, campos, hipervínculos, saltos de página o cambios controlados aparecen bloqueados. Al editar un párrafo se conserva su estilo y el formato de su primer fragmento; el formato intermedio (negritas parciales) se unifica.
- XLSX: al editar se elimina calcChain.xml y se pide recálculo al abrir; las fórmulas dependientes se actualizan al abrir en Excel.
- Guardar escribe sobre el archivo original; si no hay permiso de escritura, ofrece «Guardar como».

## Fase 1: editor Word (DOCX)
Barra de herramientas: deshacer/rehacer, estilo (Normal, Título, Título 1-3), fuente, tamaño, negrita, cursiva,
subrayado, tachado, color de texto, resaltado, alineación (izq/centro/der/justificado), listas con viñetas y
numeradas (sangrar/anular sangría), interlineado, imagen, tablas (insertar, filas/columnas, eliminar),
buscar y reemplazar, quitar formato y borrar párrafo.
- Intro divide el párrafo; Retroceso al inicio lo une con el anterior.
- Solo se reescriben los párrafos/tablas modificados; el resto del archivo queda byte a byte igual.
- Se muestran bloqueados (solo borrables): imágenes y objetos existentes, campos, hipervínculos, saltos de página,
  controles de contenido y cambios controlados. Tablas con celdas combinadas o anidadas: solo se edita el texto.
- Al reescribir un párrafo se pierden sus marcadores internos (marcadores/bookmarks, correctores).
- Sin paginación ni encabezados/pies (vista de flujo continuo).

## Fase 2: editor Excel (XLSX)
- Barra de fórmulas (celda activa + entrada), cabeceras de columna/fila, selección de rango (botón «Rango» o pulsación larga).
- Cálculo de fórmulas: operadores + - * / ^ & % y comparaciones; SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, COUNTBLANK,
  PRODUCT, MEDIAN, IF, IFERROR, AND, OR, NOT, ABS, ROUND/UP/DOWN, INT, MOD, POWER, SQRT, EXP, LN, LOG, LOG10, PI,
  LEN, UPPER, LOWER, TRIM, LEFT, RIGHT, MID, CONCAT(ENATE), REPT, SUBSTITUTE, FIND, SEARCH, VALUE, TEXT, SUMIF,
  COUNTIF, AVERAGEIF, VLOOKUP, INDEX, MATCH, TODAY, NOW, DATE, YEAR, MONTH, DAY, ISBLANK/ISNUMBER/ISTEXT/ISERROR.
  Funciones no soportadas muestran el último valor guardado en el archivo (o #NAME?). Se guardan los resultados en caché.
- Formato: negrita, cursiva, subrayado, color de texto y relleno, tamaño, alineación, bordes, formatos numéricos
  (número, miles, moneda, porcentaje, fecha, hora, texto) y +/− decimales.
- Estructura: insertar/eliminar filas y columnas (las referencias de fórmulas se actualizan), ordenar, copiar/cortar/pegar
  (también desde/hacia el portapapeles del sistema), borrar, Σ autosuma, insertador de funciones, buscar y reemplazar,
  hojas nuevas y renombrar (pulsación larga en la pestaña), deshacer/rehacer (25 pasos).
- Límites: sin filtros ni paneles inmovilizados ni combinar celdas (las combinaciones existentes se conservan); el ancho de
  columna se conserva pero no se edita; el texto enriquecido dentro de una celda se aplana al guardar las hojas modificadas;
  formato condicional, validaciones y gráficos se conservan pero no se desplazan al insertar/eliminar filas o columnas.
