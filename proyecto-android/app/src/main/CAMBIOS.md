# Cambios de esta versión

## Filtros
- Con un filtro activo (Imágenes, Vídeos, Audio, Documentos, Comprimidos, Otros) **ya no se muestran carpetas**.
- Pulsar un chip (también si ya estaba marcado) abre la lista de extensiones presentes en la carpeta, con contador; se pueden marcar varias. El chip muestra la selección («Documentos · docx, csv»).
- **Comprimidos** incluye zip, rar, 7z, tar, gz, tgz, bz2, xz, zst, lz, z, cab y los paquetes apk, apks, xapk, apkm, aab.
- Nuevo chip **Otros** (archivos que no encajan en ninguna categoría).

## Favoritos
- Lista desplegable anclada a la estrella (ya no es un diálogo). Icono según el tipo, ruta en gris y ✕ para quitar.
- Admite **archivos y carpetas**. Los favoritos antiguos se migran solos (se leen como carpetas).
- Se añaden/quitan desde el menú contextual (selección › ⋮) o, para la carpeta actual, desde el menú ⋮ de la barra.
- Estrella dorada sobre el icono de cada favorito en la lista de archivos.
- Al renombrar o mover un favorito (o una carpeta que contiene favoritos) la ruta se actualiza. Los que ya no existen salen en gris y se pueden limpiar con un botón.
- Pulsación larga en un favorito: ir a su carpeta.

## Integración con otras apps (nueva `ActividadExterna`, en su propia tarea)
- **Guardar como… / Compartir con…**: la app aparece como destino. Nombre editable, «Guardar aquí», evita sobrescribir («nombre (1)»), recuerda la última carpeta de guardado y avisa a galería/descargas (MediaScanner).
- **Elegir archivo** (GET_CONTENT / OPEN_DOCUMENT): selección simple o múltiple, respetando el tipo MIME que pida la otra app.
- **Elegir carpeta** (OPEN_DOCUMENT_TREE).
- **Abrir con…**: imágenes, vídeo, audio, texto/html/json/xml/csv, PDF, zip, docx, xlsx. Si llega un `content://` sin ruta real se copia a la caché y se ofrece «Abrir» o «Guardar en el dispositivo…».
- En estos modos el orden por defecto es «más recientes primero» y la app arranca en Descargas (o en la última carpeta de guardado).
- La propia app se excluye de sus selectores «Abrir con…» y «Compartir con…».

## Fase 2: proveedor de documentos (`ProveedorDocumentos`)
- El almacenamiento interno y las tarjetas SD aparecen como «Gestor · Interno / SD» en el menú lateral del selector de archivos del sistema de cualquier app (guardar, abrir, elegir carpeta).
- Solo el sistema (permiso MANAGE_DOCUMENTS) puede hablar con él; las apps reciben acceso únicamente a lo que el usuario elige.
- Borrar desde otra app envía a la papelera de Gestor (recuperable).

## Otros
- `REQUEST_INSTALL_PACKAGES` añadido al manifest (sin él no se puede abrir un APK desde la app).
- Los modos externos ya no pisan la «última carpeta» con la que se abre la app normalmente.

## Archivos nuevos
`ActividadExterna.kt`, `data/SolicitudExterna.kt`, `data/ResolvedorUri.kt`, `data/ProveedorDocumentos.kt`,
`ui/AnfitrionExterno.kt`, `ui/PermisoArchivosUi.kt`, `res/layout/popup_favoritos.xml`, `res/layout/item_favorito.xml`,
`res/drawable/bg_popup_favoritos.xml`.

## Archivos modificados
`AndroidManifest.xml`, `MainActivity.kt`, `data/ArchivoItem.kt`, `data/RepositorioFavoritos.kt`, `data/AdaptadorArchivos.kt`,
`data/GestorArchivos.kt`, `data/Ajustes.kt`, `data/Almacenamientos.kt`, `ui/PantallaGestor.kt`,
`res/layout/fragment_gestor.xml`, `res/layout/item_archivo.xml`, `res/menu/menu_contextual_archivo.xml`,
`res/menu/menu_mas.xml`, `res/values/strings.xml`.

## Nota
Este código no se pudo compilar en el entorno donde se escribió (sin SDK de Android). Si Android Studio marca algún error al compilar, pásamelo y lo corrijo.
