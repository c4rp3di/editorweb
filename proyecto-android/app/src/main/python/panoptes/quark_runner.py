"""
Scoring con quark-engine (§6.2 del plan) — "quark (scoring)".

⚠️ ESTADO: esqueleto, no una integración verificada. Dos motivos reales:

1. La API Python de quark-engine ha cambiado bastante entre versiones y
   no tengo forma de confirmar aquí, sin poder ejecutar nada, que las
   llamadas de abajo coinciden exactamente con la superficie de
   quark-engine==23.9.1. Revísalo contra la versión real instalada
   (`help(quark.core.quark.Quark)` desde un intérprete con el paquete
   instalado) antes de confiar en el resultado.

2. quark-engine necesita un directorio de REGLAS (JSON) que normalmente
   se descargan aparte con su herramienta `freshquark` desde
   github.com/quark-engine/quark-rules — ese paquete NO las trae
   embebidas. Descargarlas en tiempo de ejecución en el dispositivo no
   encaja con "sin telemetría / todo verificado" del plan, así que la
   integración aquí asume que ALGUIEN empaquetó un subconjunto de reglas
   como asset de la app (p.ej. assets/quark-rules/*.json) y pasa esa ruta
   ya extraída a filesDir como `rules_dir`. Si no hay reglas, la función
   se degrada con gracia en vez de fallar.

Contrato: run(apk_path: str, rules_dir: str | None) -> str (JSON).
"""
import json
import os


def run(apk_path, rules_dir):
    if not rules_dir or not os.path.isdir(rules_dir):
        return json.dumps({
            "skipped": True,
            "reason": "sin reglas quark empaquetadas (rules_dir vacío o inexistente)"
        })

    reglas = [f for f in os.listdir(rules_dir) if f.endswith(".json")]
    if not reglas:
        return json.dumps({"skipped": True, "reason": "rules_dir sin ficheros .json"})

    try:
        from quark.core.quark import Quark
        from quark.core.struct.ruleobject import RuleObject
    except Exception as e:
        return json.dumps({"error": f"no se pudo importar quark-engine: {type(e).__name__}: {e}"})

    try:
        quark = Quark(apk_path)
    except Exception as e:
        return json.dumps({"error": f"Quark() falló: {type(e).__name__}: {e}"})

    evaluadas = 0
    con_coincidencias = 0
    errores = []

    for nombre in reglas:
        try:
            rule = RuleObject(os.path.join(rules_dir, nombre))
            quark.run(rule)
            evaluadas += 1
            # ⚠️ Punto más incierto de todo el archivo: el nombre exacto del
            # método/atributo para saber si una regla "disparó" varía entre
            # versiones de quark-engine (ha sido quark.quark_analysis.*,
            # rule.check_item, etc. en distintos releases). Ajusta esto
            # contra la versión real antes de confiar en "con_coincidencias".
            if getattr(quark, "quark_analysis", None) and getattr(quark.quark_analysis, "crime_description", None):
                con_coincidencias += 1
        except Exception as e:
            errores.append(f"{nombre}: {type(e).__name__}: {e}")

    try:
        quark.close()
    except Exception:
        pass

    return json.dumps({
        "rules_evaluated": evaluadas,
        "rules_matched": con_coincidencias,
        "errors": errores[:20],  # no dejar que un error por regla infle el JSON
    })
