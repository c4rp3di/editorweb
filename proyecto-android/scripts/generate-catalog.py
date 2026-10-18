#!/usr/bin/env python3
"""
Regenera module_catalog.json a partir de los releases deps-* publicados en
GitHub Releases (§8.4 del plan). Lee el SHA-256 del ZIP y del DEX de los
ficheros .sha256 que build-module-*.yml adjunta a cada release, y escribe
un catálogo listo para subir como release 'catalog-latest'.

Uso: python3 generate-catalog.py <owner>/<repo> > module_catalog.json
Requiere `gh` autenticado (gh auth login) o la variable GH_TOKEN.
"""
import json
import re
import subprocess
import sys
from datetime import datetime, timezone

# Metadatos que no vienen de GitHub Releases (tamaño exacto, entry_class,
# provides/requires...) y que SÍ hay que mantener a mano aquí al añadir un
# módulo nuevo. El script solo rellena sha256 / signature_sha256 / url.
MODULOS_CONOCIDOS = {
    "jadx": {
        "name": "Descompilador jadx",
        "description": "Convierte DEX a Java legible",
        "entry_class": "com.carpe.panoptes.modules.jadx.JadxAnalyzer",
        "provides": ["decompiler.java"],
        "requires": ["shell:dexlib2"],
        "requires_root": False,
        "requires_shim": False,
    },
    "apktool": {
        "name": "apktool",
        "description": "Decodifica recursos y manifest",
        "entry_class": "com.carpe.panoptes.modules.apktool.ApktoolAnalyzer",
        "provides": ["resource.decoder"],
        "requires": ["shell:dexlib2"],
        "requires_root": False,
        "requires_shim": False,
    },
    "radare2": {
        "name": "radare2",
        "description": "Análisis avanzado de binarios nativos",
        "entry_class": "com.carpe.panoptes.modules.radare2.Radare2Analyzer",
        "provides": ["native.analysis"],
        "requires": ["shell:dexlib2"],
        "requires_root": False,
        "requires_shim": True,
    },
    "frida-server": {
        "name": "Frida server",
        "description": "Instrumentación dinámica (requiere root)",
        "entry_class": "com.carpe.panoptes.modules.frida.FridaController",
        "provides": ["dynamic.instrumentation"],
        "requires": ["shell:dexlib2"],
        "requires_root": True,
        "requires_shim": False,
    },
}


def gh(*args):
    return subprocess.run(["gh", *args], capture_output=True, text=True, check=True).stdout


def main():
    if len(sys.argv) != 2:
        print("Uso: generate-catalog.py <owner>/<repo>", file=sys.stderr)
        sys.exit(1)
    repo = sys.argv[1]

    releases = json.loads(gh("release", "list", "--repo", repo, "--json", "tagName", "--limit", "200"))
    modules = []

    for rel in releases:
        tag = rel["tagName"]
        m = re.match(r"^deps-([a-z0-9-]+)-(.+)$", tag)
        if not m:
            continue
        module_id, version = m.group(1), m.group(2)
        if module_id not in MODULOS_CONOCIDOS:
            print(f"aviso: módulo desconocido '{module_id}' en tag '{tag}', se omite", file=sys.stderr)
            continue

        assets = json.loads(gh("release", "view", tag, "--repo", repo, "--json", "assets"))["assets"]
        zip_asset = next((a for a in assets if a["name"].endswith("-module.zip")), None)
        zip_sha_asset = next((a for a in assets if a["name"] == "module.zip.sha256"), None)
        dex_sha_asset = next((a for a in assets if a["name"] == "classes.dex.sha256"), None)
        if not (zip_asset and zip_sha_asset and dex_sha_asset):
            print(f"aviso: release '{tag}' incompleto, se omite", file=sys.stderr)
            continue

        subprocess.run(["gh", "release", "download", tag, "--repo", repo,
                         "--pattern", "*.sha256", "--dir", "/tmp/catalog-dl", "--clobber"],
                        check=True)
        with open(f"/tmp/catalog-dl/module.zip.sha256") as f:
            sha_zip = f.read().split()[0]
        with open(f"/tmp/catalog-dl/classes.dex.sha256") as f:
            sha_dex = f.read().split()[0]

        meta = MODULOS_CONOCIDOS[module_id]
        modules.append({
            "id": module_id,
            "name": meta["name"],
            "description": meta["description"],
            "version": version,
            "size_bytes": zip_asset["size"],
            "sha256": sha_zip,
            "signature_sha256": sha_dex,
            # Construido a mano (no desde el JSON de `gh`): así nos aseguramos de
            # que cae en github.com/.../releases/download/..., el único patrón
            # que ModuleDownloader.ALLOWED_HOSTS acepta.
            "url": f"https://github.com/{repo}/releases/download/{tag}/{zip_asset['name']}",
            "entry_class": meta["entry_class"],
            "provides": meta["provides"],
            "requires": meta["requires"],
            "requires_root": meta["requires_root"],
            "requires_shim": meta["requires_shim"],
        })

    catalog = {
        "schema": 1,
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "shell_min_version": 1,
        "modules": modules,
    }
    print(json.dumps(catalog, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
