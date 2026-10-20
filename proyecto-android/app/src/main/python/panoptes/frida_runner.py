"""
Control de frida-server desde dentro del propio dispositivo analizado,
vía los bindings Python oficiales de Frida (paquete `frida` en PyPI),
embebidos con Chaquopy.

⚠️⚠️ ESTA ES LA PIEZA MÁS INCIERTA DE TODO EL PROYECTO, MÁS QUE
quark_runner.py. Léelo antes de asumir que esto funciona:

El paquete `frida` de PyPI no es Python puro: envuelve una extensión
nativa (_frida) compilada en Rust/C++ para cada combinación de SO+arch.
Chaquopy solo mantiene wheels precompilados para un conjunto curado de
paquetes populares — es muy posible que `frida` NO esté entre ellos para
Android/arm64, porque no es algo que tenga sentido ejecutar "dentro" de
un dispositivo Android normalmente: el patrón de uso real y soportado por
el propio proyecto Frida es controlar frida-server (que SÍ corre en el
dispositivo) DESDE UN ORDENADOR, por USB o red local — no controlarlo
desde dentro del mismo dispositivo.

Si `pip install frida==16.1.4` falla en la build de F5/F6, la
arquitectura realista para "frida-server como módulo" (§9 del plan, F6)
es: este módulo arranca y expone frida-server en el dispositivo; el
usuario conecta DESDE SU PC con `frida -H <ip-del-movil>:27042 ...` o vía
`adb forward`. FridaController.kt ya deja esto como ModuleResult incluso
si esta función falla — no es un callejón sin salida, solo un cambio de
quién controla la sesión.

Contrato: run(target_package, script_path, timeout_seconds) -> str (JSON)
con una lista de eventos {"type": "url"|"pin_bypass"|"log", ...} tal y
como los emite ssl-unpinning-and-log.js vía send().
"""
import json
import time


def run(target_package, script_path, timeout_seconds):
    eventos = []
    try:
        import frida
    except Exception as e:
        return json.dumps({
            "error": f"paquete 'frida' no disponible en Chaquopy: {type(e).__name__}: {e}",
            "fallback": "usa frida-server desde un PC conectado al dispositivo (ver cabecera de este archivo)"
        })

    try:
        device = frida.get_local_device()
    except Exception as e:
        return json.dumps({"error": f"no se pudo conectar al frida-server local: {type(e).__name__}: {e}"})

    try:
        with open(script_path, "r") as f:
            codigo_script = f.read()
    except Exception as e:
        return json.dumps({"error": f"no se pudo leer el script: {type(e).__name__}: {e}"})

    def on_message(message, data):
        if message.get("type") == "send":
            eventos.append(message.get("payload"))

    session = None
    pid = None
    try:
        pid = device.spawn([target_package])
        session = device.attach(pid)
        script = session.create_script(codigo_script)
        script.on("message", on_message)
        script.load()
        device.resume(pid)

        time.sleep(timeout_seconds)
    except Exception as e:
        return json.dumps({"error": f"fallo durante la instrumentación: {type(e).__name__}: {e}", "events": eventos})
    finally:
        try:
            if session:
                session.detach()
        except Exception:
            pass
        try:
            if pid:
                device.kill(pid)
        except Exception:
            pass

    return json.dumps({"events": eventos})
