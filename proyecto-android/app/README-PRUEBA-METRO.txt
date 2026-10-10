PRUEBA AISLADA: MAPA DE METRO Y CONVOYES ESTIMADOS

Este ZIP conserva la estructura del proyecto Fusion para que puedas importarlo como un proyecto separado. La pantalla principal se ha sustituido por una vista exclusiva del trazado de Metro, sin mapa de calles ni capas de autobuses.

Uso:
1. Importa este ZIP en Fusion como proyecto nuevo/separado.
2. Ejecuta la app en Android.
3. Pulsa una línea en el mapa o en la leyenda inferior.
4. La pantalla consultará los teleindicadores públicos de las estaciones de esa línea y dibujará posiciones estimadas cuando los datos permitan calcularlas.

Importante:
- Los marcadores son estimaciones basadas en tiempos de llegada y geometría, NO posiciones GPS oficiales ni IDs confirmados de trenes.
- Esta prueba usa el endpoint público de teleindicadores y el servicio geográfico de la red.
- No llama a la API privada de Tren Digital coches/infoOcupacion, porque todavía no hemos validado una llamada real desde el contexto TLS de la app oficial. Tampoco extrae ni incorpora certificados o claves privadas.
- La geometría requiere conexión a Internet y el acceso a los servicios puede cambiar.
- El proyecto original no se modifica; este ZIP es una copia separada para experimentar.
