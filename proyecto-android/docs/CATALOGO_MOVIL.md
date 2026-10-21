# Catálogo firmado: todo desde el móvil

0. Publica los módulos: en GitHub → Actions → **Build module jadx** → Run workflow, y lo mismo con
   **Build module apktool**. Cada uno compila el módulo y crea la release `deps-<id>-<versión>`; al
   acabar lanzan solos *Publish catalog* (que fallará hasta que hagas el paso 3: es normal la
   primera vez, vuelve a lanzarlo después).

1. En la app: **Ajustes → Catálogo de módulos**. Escribe tu usuario de GitHub y el repositorio.
2. Pulsa **Generar par de claves**. La clave pública se rellena sola. Copia la clave privada que
   aparece en el diálogo (no se guarda en la app) y pulsa **Guardar**.
3. En GitHub (navegador del móvil): repositorio → Settings → Secrets and variables → Actions →
   New repository secret. Nombre `CATALOG_SIGNING_KEY`, valor: la clave privada copiada.
4. Repositorio → Actions → **Publish catalog** → Run workflow. Firma el catálogo y lo publica en la
   release `catalog-latest`. El resumen del job muestra la clave pública: debe coincidir con la de la app.
5. Si pierdes la clave privada: genera otro par, cambia el secret y pulsa Guardar en la app.

Mientras no haya módulos publicados (releases `deps-<id>-<versión>`), el catálogo sale vacío.

## Ver lo que generan los módulos

En el detalle de un análisis: **Explorar código, recursos y DEX generados**. Navega por carpetas, abre
ficheros de texto (Java de jadx, XML de apktool...), busca texto en todo el código (lupa) y exporta una
carpeta o fichero como ZIP (icono de descarga).

## Actualizar la app sin perder los módulos (v1.0.17)
Desde la v1.0.17 la app se firma siempre con la misma clave de depuración (incluida en `app/build.gradle`).
La primera vez que pases de una versión anterior a esta hay que desinstalar una última vez (las anteriores
tenían firmas distintas); a partir de ahí las actualizaciones se instalan encima y los módulos se conservan.
Tras actualizar, vuelve a lanzar «Build module jadx» y «Build module apktool» y reinstala ambos desde Módulos.
En cada análisis, la carpeta de cada módulo incluye `_panoptes-log.txt` con el resultado o el motivo del fallo.
