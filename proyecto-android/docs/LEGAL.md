# Legal

Mismo texto que muestra el diálogo de primer uso de la app
(`ui/settings/LegalDialog.kt`) — este fichero es la versión de referencia
para el repo; si cambias uno, cambia el otro.

## Uso personal exclusivamente

APK Panoptes no se distribuye en Google Play ni en tiendas de terceros.
Su propósito es el análisis de APKs propios o de aplicaciones sobre las
que el usuario tenga autorización explícita.

## Sin ataque a servicios

Panoptes realiza análisis informativo (endpoints, permisos, ofuscación).
No fuzzea, no ataca, no automatiza interacciones con servidores de
terceros.

## SSL unpinning y Frida

La instrumentación dinámica con Frida (módulo `frida-server`, F6) puede
violar los términos de servicio de apps de terceros (bancos, mensajería,
DRM) e incluso leyes de acceso no autorizado en algunas jurisdicciones,
aunque el dispositivo sea de tu propiedad. Úsala solo sobre apps propias
o con autorización escrita.

## RASP corporativos

Ejecutar frida-server en un dispositivo de empresa puede activar alarmas
de seguridad. No lo hagas.

## Sin telemetría

Panoptes no envía datos a ningún servidor propio. Las descargas de
módulos van exclusivamente a github.com y sus CDNs (ver
`ModuleDownloader.ALLOWED_HOSTS`).

## Aislamiento

Cada análisis se ejecuta en `filesDir/analysis/<id>/`. No hay
purga automática implementada todavía (quedó fuera de F7 por alcance) —
si te importa el espacio en disco, bórralo a mano o añade esa limpieza en
`AnalysisOrchestrator`.

## Secretos candidatos

Los "secretos candidatos" que detecta el escáner de strings (F1) y se
exportan en los informes (F7) son heurísticas (patrón + entropía), no
confirmaciones. La UI los enmascara por defecto; el export JSON los
incluye completos porque es un dato que generas sobre tu propio APK para
decidir qué rotar, igual que haría `gitleaks` o `truffleHog` sobre tu
propio repositorio.
