#!/usr/bin/env python3
"""
Firma del catálogo de módulos (auditoría H-02).

La app solo acepta el catálogo remoto si la firma publicada junto a él se verifica con la
clave pública guardada en la app (Ajustes) (ECDSA P-256 + SHA-256).

Uso:
  python3 sign-catalog.py genkey [fichero_clave_privada.pem]
      Genera el par de claves. Guarda la privada en el fichero (por defecto
      catalog-signing-key.pem, permisos 600) e IMPRIME la clave pública en Base64 para
      pegarla en la app: Ajustes → Clave pública del catálogo. La privada NO debe subirse al repositorio:
      guárdala como secret de CI (p.ej. CATALOG_SIGNING_KEY) o en un gestor de secretos.

  python3 sign-catalog.py sign module_catalog.json clave_privada.pem > module_catalog.json.sig
      Firma el catálogo (los bytes exactos del fichero). Publica el .sig como asset
      'module_catalog.json.sig' en la misma release 'catalog-latest' que el catálogo.
      Hay que firmar de nuevo cada vez que cambie el catálogo.

  python3 sign-catalog.py pubkey <clave_privada>
      Imprime la clave pública (Base64) correspondiente a la privada.

  python3 sign-catalog.py verify module_catalog.json module_catalog.json.sig <clave_publica_b64>
      Comprueba una firma (útil en CI antes de publicar).

<clave_privada> puede ser: un fichero PEM, un fichero con la clave PKCS8 DER en Base64 (lo
que genera la app en Ajustes), o 'env:NOMBRE' para leerla de una variable de entorno (CI).

Requiere el paquete 'cryptography' (pip install cryptography).
"""
import base64
import os
import sys

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def genkey(ruta):
    clave = ec.generate_private_key(ec.SECP256R1())
    pem = clave.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    fd = os.open(ruta, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    publica = clave.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    print(f"Clave privada guardada en {ruta} (no la subas al repo).", file=sys.stderr)
    print("Clave pública para pegar en la app (Ajustes → Catálogo de módulos):", file=sys.stderr)
    print(base64.b64encode(publica).decode())


def cargar_privada(origen):
    if origen.startswith("env:"):
        crudo = os.environ.get(origen[4:], "").encode()
    else:
        with open(origen, "rb") as f:
            crudo = f.read()
    crudo = crudo.strip()
    if not crudo:
        print("clave privada vacía", file=sys.stderr)
        sys.exit(1)
    if crudo.startswith(b"-----BEGIN"):
        clave = serialization.load_pem_private_key(crudo, password=None)
    else:
        clave = serialization.load_der_private_key(base64.b64decode(b"".join(crudo.split())), password=None)
    if not isinstance(clave, ec.EllipticCurvePrivateKey) or clave.curve.name != "secp256r1":
        print("la clave debe ser ECDSA P-256", file=sys.stderr)
        sys.exit(1)
    return clave


def publica_b64(clave):
    der = clave.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return base64.b64encode(der).decode()


def firmar(catalogo, ruta_clave):
    clave = cargar_privada(ruta_clave)
    with open(catalogo, "rb") as f:
        datos = f.read()
    firma = clave.sign(datos, ec.ECDSA(hashes.SHA256()))  # DER, lo que verifica SHA256withECDSA
    print(base64.b64encode(firma).decode())


def verificar(catalogo, ruta_firma, publica_b64):
    publica = serialization.load_der_public_key(base64.b64decode(publica_b64))
    with open(catalogo, "rb") as f:
        datos = f.read()
    with open(ruta_firma, "r") as f:
        firma = base64.b64decode(f.read().strip())
    try:
        publica.verify(firma, datos, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        print("FIRMA NO VÁLIDA", file=sys.stderr)
        sys.exit(1)
    print("firma válida", file=sys.stderr)


def main():
    args = sys.argv[1:]
    if args[:1] == ["genkey"] and len(args) in (1, 2):
        genkey(args[1] if len(args) == 2 else "catalog-signing-key.pem")
    elif args[:1] == ["sign"] and len(args) == 3:
        firmar(args[1], args[2])
    elif args[:1] == ["pubkey"] and len(args) == 2:
        print(publica_b64(cargar_privada(args[1])))
    elif args[:1] == ["verify"] and len(args) == 4:
        verificar(args[1], args[2], args[3])
    else:
        print(__doc__, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
