# Nodo de Verdad · app Android nativa (v1.0)

Enseña **quién recibe señales de tu móvil y quién hay detrás de cada empresa**.
Todo ocurre en el dispositivo: sin cuentas, sin servidores propios, sin analítica, sin anuncios.

Proyecto Gradle (Kotlin, vistas nativas, sin librerías raras) preparado para compilarse con **Fusion / EditorWeb** (GitHub Actions: Gradle 8.7, AGP 8.5.0, Kotlin 1.9.24, compileSdk 34, minSdk 24).

## Cómo compilarlo con Fusion

1. En Fusion: **Abrir .zip** y elige este archivo. Detecta el proyecto Android (paquete `org.nododeverdad.app`).
2. Comprueba nombre e identidad en el menú de Android (puedes cambiar el paquete).
3. **Compilar APK** (GitHub Actions). El APK sale como *release* del repo.
4. Instálalo en el móvil (permite «instalar apps desconocidas»).

Si la compilación falla, usa el **Diagnóstico de compilación** de Fusion y pásame el mensaje del log: lo corrijo.

## Qué hay dentro (5 pestañas)

| Pestaña | Qué hace |
|---|---|
| **Radar** | Radar animado con las empresas que han recibido señales de tu móvil, índice 0–100, cifras, categorías, apps más habladoras, tarjeta para compartir |
| **Rastro** | Grafo interactivo: tus apps → empresas → país de la sede (con vista de lista accesible) |
| **Quién** | Directorio de 68 empresas de seguimiento con buscador y filtros; ficha en 3 niveles (Titular · Historia · Evidencia) y dos registros (15 / 70 años) |
| **Cambios** | Altas y bajas en tu móvil + cronología de operaciones corporativas y resoluciones de autoridades |
| **Aprende** | Retos, quiz, glosario sin jerga, promesas y límites |

Extras: 3 perfiles (Explorer, Reader, Assisted), alto contraste, texto grande, lectura en voz alta, exportación JSON abierta (esquema `device-export` del paquete de diseño), modo escudo opcional (bloquear categorías), datos de ejemplo claramente marcados.

## Cómo funciona el radar (y por qué es seguro)

- Usa `VpnService` **solo para las consultas DNS**: ruta únicamente a una IP virtual de DNS; el resto del tráfico no se toca ni se descifra.
- Reenvía cada consulta al DNS que ya usabas (el de tu red) y devuelve la respuesta.
- De cada consulta solo mira el **nombre del servidor**. Si es un rastreador conocido, anota (app, dominio, empresa, nº de consultas). Si no, solo suma a un contador diario **sin guardar el dominio** (no hay historial de navegación).
- La app que hizo la consulta se obtiene con `getConnectionOwnerUid` (Android 10+). En Android 7–9 las consultas se agrupan como «Sistema».
- Datos en SQLite local, borrado automático (7/30/90 días) y borrado total en un toque.
- Los datos de ejemplo no se pueden exportar ni compartir.

## Importante: sin probar en dispositivo

Este código se escribió sin acceso a un compilador Android ni a un móvil. Se han hecho comprobaciones estáticas (llaves, recursos, XML, referencias), pero **puede haber errores de compilación o de ejecución**. Lista de pruebas recomendada:

- [ ] Compila sin errores en Fusion.
- [ ] Onboarding → elegir perfil → pantalla Radar.
- [ ] «Ver un ejemplo»: aparecen radar, grafo, directorio, altas/bajas.
- [ ] Encender radar: diálogo de VPN → icono de llave → notificación fija.
- [ ] Abrir Chrome/una app con anuncios: tras unos minutos aparecen empresas.
- [ ] Verificar con un proxy/sniffer externo que **no sale nada** salvo las consultas DNS reenviadas a tu resolutor.
- [ ] Apagar radar y comprobar que el móvil recupera internet normal.
- [ ] Probar con «DNS privado» en Automático y en estricto (en estricto debe salir el aviso).
- [ ] Probar modo escudo con una categoría y comprobar que no rompe lo esencial.
- [ ] Compartir tarjeta y exportar JSON (con y sin nombres de apps).
- [ ] TalkBack: recorrer Radar, ficha de empresa y vista Lista del grafo.

## Ampliar la base de empresas

Edita `Knowledge.kt`. Cada empresa:

```kotlin
c("id", "Nombre", "Matriz o null", "País", esEEE, Cat.PUB,
  "Qué hace, en una frase.",
  "dominio1.com dominio2.net:ANA")   // sufijos; «:CAT» fuerza otra categoría
```

Reglas del proyecto: solo hechos públicos con año y fuente, nunca afirmar delitos, y mantener el «qué NO significa». Cada nueva operación o resolución va en `events`.

## Antes de publicar

- Fija un **canal de contacto para correcciones y derecho de réplica** (la app lo menciona en «Acerca de»).
- Elige licencia (el diseño propone AGPL-3.0) y añade el archivo `LICENSE`.
- Google Play exige declarar el uso de `VpnService` y de servicios en primer plano; F-Droid o APK firmado evitan esa dependencia.
- Revisión jurídica de los textos sobre empresas (multas, matrices) y verificación de las cifras con las fuentes oficiales.
- Auditoría de seguridad y prueba de no-fuga antes de la 1.0 pública.
