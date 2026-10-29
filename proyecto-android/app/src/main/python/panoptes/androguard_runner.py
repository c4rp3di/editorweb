"""
Parsing "alternativo" del manifest/certificado vía androguard (§6.2 del
plan). No duplica lo que ya hace ManifestParser.kt con PackageManager en
F1 — se usa solo para lo que PackageManager no expone de forma fiable:
info de firma/certificado (v1/v2/v3) y permisos *declarados* (no solo los
*solicitados*, que es lo único que PackageManager da fácil).

Usa androguard.core.apk.APK directamente (no androguard.misc.AnalyzeAPK):
esa segunda forma hace un análisis completo del DEX, que es justo lo que
ya cubre dexlib2 en el shell (F1) — pedirle lo mismo a androguard sería
trabajo duplicado y mucho más lento.

Contrato: run(apk_path: str) -> str (JSON). Nunca lanza — cualquier error
se devuelve como {"error": "..."} para que el lado Kotlin no tenga que
lidiar con excepciones de Python cruzando el puente de Chaquopy.
"""
import json


def run(apk_path):
    try:
        from androguard.core.apk import APK
    except Exception as e:
        return json.dumps({"error": f"no se pudo importar androguard: {type(e).__name__}: {e}"})

    try:
        apk = APK(apk_path)
    except Exception as e:
        return json.dumps({"error": f"{type(e).__name__}: {e}"})

    try:
        certificados = len(apk.get_certificates_der_v2() or [])
    except Exception:
        certificados = 0

    try:
        resultado = {
            "package_name": apk.get_package(),
            "version_name": apk.get_androidversion_name(),
            "version_code": apk.get_androidversion_code(),
            "min_sdk": apk.get_min_sdk_version(),
            "target_sdk": apk.get_target_sdk_version(),
            "permissions_requested": apk.get_permissions(),
            "permissions_declared": list(apk.get_declared_permissions() or []),
            "is_signed_v1": apk.is_signed_v1(),
            "is_signed_v2": apk.is_signed_v2(),
            "is_signed_v3": apk.is_signed_v3(),
            "certificate_count": certificados,
        }
        return json.dumps(resultado)
    except Exception as e:
        return json.dumps({"error": f"{type(e).__name__}: {e}"})
