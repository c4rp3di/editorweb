# AdBlock Soberano+ (2.0) — notas de integración

Módulos: :vigia-core (+3 ganchos, sin BouncyCastle) · :vigia-mitm (nuevo) · :app (AdBlock 1.0, intacto) · :app (AdBlock Soberano+, applicationId com.vigia.adblockplus).

NO compilado: el entorno no tenía Android SDK ni Kotlin. Primera compilación = posibles errores menores.

Cambios en :vigia-core (49 líneas): interfaz DesvioTcp + ConfigVpn.desvioTcp; FiltroTrafico.dominioBloqueadoEnDns (por defecto = dominioBloqueado);
Reenviador.conectar consulta el desvío; las conexiones desviadas no pasan por la retención SNI del núcleo.

Prueba mínima en dispositivo: 1) activar MitM, exportar/instalar CA; 2) abrir Chrome y una página con anuncios; 3) logcat tag-less: comprobar
que el handshake con el proxy termina y que aparece SNI; 4) abrir una app con pinning y comprobar que entra en «Apps sin descifrar».
