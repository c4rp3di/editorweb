# Informe de validación de NetLens 2.2-review

Fecha: 2026-10-28

## Resultado

**Revisión parcial satisfactoria de componentes; build Android completo no ejecutado.** No se genera ni se presenta un APK como válido.

## Comprobaciones que sí se ejecutaron

1. `Dpi.kt`, `Store.kt`, `Engine.kt` y `NetworkTools.kt` se compilaron juntos con `kotlinc` y pequeños stubs de API Android; la prueba de integración aislada confirma que un SNI bloqueado no se encola para salida y que un ClientHello permitido y fragmentado se libera completo y en orden.
2. Las cinco pruebas de `DpiTlsTest.kt` se ejecutaron con un arnés local con aserciones compatibles: SNI normalizado, ALPN, TLS 1.3, mensaje parcial, ClientHello repartido en varios registros TLS, bandera ECH y robustez frente a 2.000 entradas aleatorias.
3. `NetworkTools.kt` se compiló con stubs mínimos de preferencias Android y pasó tests de regla por subdominio, especificidad, ámbito de paquete y frontera del nombre de dominio.
4. `MitmProxy.kt` se compiló con stubs de `android.util.Log`, BouncyCastle y los tipos locales que necesita.

Estos arneses no sustituyen una compilación con Android Gradle Plugin ni ejercitan el proveedor TLS real del dispositivo.

## Bloqueos para declarar una release

- No hay ejecutable Gradle, Gradle Wrapper ni Android SDK disponible en el entorno, por lo que no puede confirmarse un build APK.
- Debe probarse el TLS server-side SNI de `ExtendedSSLSession`, la instalación de la CA y el pinning con un conjunto conocido de apps de laboratorio.
- El proxy MITM sólo termina y reenvía TLS; no interpreta URLs, streams HTTP/2 ni cuerpos. No debe confundirse con un filtro de contenido HTTPS.
- IPv6 y las rutas alternativas a la VPN no están validadas. La aplicación no ofrece garantía de ausencia de fugas.
- ECH puede impedir ver el hostname real; el filtrado genérico se apoya en SNI visible y en los nombres DNS/IP observados.

## Prueba manual sugerida

En un dispositivo de laboratorio, activa **Herramientas → Protección**, añade `-example.com`, inicia la captura para una app de prueba propia y solicita un host controlado bajo ese dominio. Comprueba que el flujo se registra como bloqueado por SNI y que el sitio permitido funciona al retirar la regla. Repite con QUIC y IPv6 para confirmar que las limitaciones se muestran correctamente; no uses la app como único control de seguridad.
