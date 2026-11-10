#!/usr/bin/env python3
"""
Fabrica de extensiones CloudStream.

Dos pasos, los dos se ejecutan en el workflow de GitHub Actions:

  preparar  Calcula el hash de cada carpeta de `extensiones/` y, si cambio
            respecto al ultimo build, sube en 1 el `version` de su
            build.gradle.kts. Guarda el estado en .factory/estado.json.

  publicar  Lee lo que genero Gradle (build/plugins.json + cada
            <extension>/build/<Nombre>.cs3), copia los .cs3 a `compiladas/`,
            reescribe las URLs, recalcula tamano y hash SHA-256, borra los
            .cs3 de extensiones eliminadas y regenera plugins.json + index.json.

Uso local:
    python3 scripts/fabrica.py preparar
    ./gradlew make makePluginsJson
    python3 scripts/fabrica.py publicar
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
import time
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
CODIGO = RAIZ / "extensiones"
PUBLICADAS = RAIZ / "compiladas"
ESTADO = RAIZ / ".factory" / "estado.json"
VERSION_RE = r"(?m)^version\s*=\s*(\d+)\s*$"


def repo_slug() -> str:
    return os.environ.get("GITHUB_REPOSITORY", "harley654321/cloudstream-extensiones")


def rama() -> str:
    return os.environ.get("GITHUB_REF_NAME", "main")


def extensiones() -> list[Path]:
    if not CODIGO.exists():
        return []
    return sorted(
        d for d in CODIGO.iterdir() if d.is_dir() and (d / "build.gradle.kts").exists()
    )


def huella(carpeta: Path) -> str:
    """SHA-256 del codigo de la extension (sin build/, sin ficheros ocultos de IDE)."""
    h = hashlib.sha256()
    ficheros = sorted(
        p for p in carpeta.rglob("*")
        if p.is_file()
        and "build" not in p.relative_to(carpeta).parts
        and ".gradle" not in p.relative_to(carpeta).parts
    )
    for f in ficheros:
        h.update(f.relative_to(carpeta).as_posix().encode("utf-8"))
        h.update(b"\0")
        h.update(f.read_bytes())
        h.update(b"\0")
    return h.hexdigest()


def sha256_archivo(f: Path) -> str:
    return "sha256-" + hashlib.sha256(f.read_bytes()).hexdigest()


def leer_version(carpeta: Path) -> int:
    import re
    texto = (carpeta / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(VERSION_RE, texto)
    return int(m.group(1)) if m else 1


def escribir_version(carpeta: Path, version: int) -> None:
    import re
    f = carpeta / "build.gradle.kts"
    texto = f.read_text(encoding="utf-8")
    if re.search(VERSION_RE, texto):
        texto = re.sub(VERSION_RE, f"version = {version}", texto, count=1)
    else:
        texto = f"version = {version}\n\n" + texto
    f.write_text(texto, encoding="utf-8")


def cargar_estado() -> dict:
    if ESTADO.exists():
        try:
            return json.loads(ESTADO.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            pass
    return {"plugins": {}}


def guardar_estado(estado: dict) -> None:
    ESTADO.parent.mkdir(parents=True, exist_ok=True)
    ESTADO.write_text(
        json.dumps(estado, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )


def preparar() -> int:
    estado = cargar_estado()
    plugins = estado.setdefault("plugins", {})
    cambiadas: list[str] = []

    print("== Versionado automatico ==")
    for carpeta in extensiones():
        # Hash ANTES de tocar el fichero: sirve para detectar cambios reales.
        h_antes = huella(carpeta)
        previo = plugins.get(carpeta.name)
        actual = leer_version(carpeta)

        if previo is None:
            nueva = max(actual, 1)
        elif previo.get("hash") != h_antes:
            nueva = max(actual, int(previo.get("version", 1))) + 1
        else:
            nueva = max(actual, int(previo.get("version", 1)))

        escribir_version(carpeta, nueva)
        # El hash se guarda DESPUES de escribir la version, para que la siguiente
        # ejecucion no vea su propio cambio de version como codigo nuevo.
        plugins[carpeta.name] = {"version": nueva, "hash": huella(carpeta)}

        hay_cambio = previo is None or previo.get("hash") != h_antes
        if hay_cambio:
            cambiadas.append(carpeta.name)
        print(f"  {carpeta.name:<32} v{nueva}{'   <- cambios' if hay_cambio else ''}")

    # Plugins que ya no existen en el repo
    for nombre in list(plugins):
        if not (CODIGO / nombre / "build.gradle.kts").exists():
            print(f"  {nombre:<32} eliminada del estado")
            plugins.pop(nombre)

    guardar_estado(estado)

    salida = os.environ.get("GITHUB_OUTPUT")
    if salida:
        with open(salida, "a", encoding="utf-8") as fh:
            fh.write(f"cambiados={'true' if cambiadas else 'false'}\n")
            fh.write(f"lista={','.join(cambiadas)}\n")

    print("Con cambios:", ", ".join(cambiadas) if cambiadas else "ninguna")
    return 0


def publicar() -> int:
    plugins_json = RAIZ / "build" / "plugins.json"
    if not plugins_json.exists():
        print("ERROR: falta build/plugins.json. Ejecuta antes ./gradlew make makePluginsJson")
        return 1

    PUBLICADAS.mkdir(exist_ok=True)
    base_raw = f"https://raw.githubusercontent.com/{repo_slug()}/{rama()}/compiladas"
    entradas = json.loads(plugins_json.read_text(encoding="utf-8"))
    publicadas: list[dict] = []

    print("== Publicando ==")
    for entrada in entradas:
        nombre = entrada.get("internalName") or entrada.get("name")
        origen = CODIGO / nombre / "build" / f"{nombre}.cs3"
        if not origen.exists():
            print(f"  AVISO: no se encontro {origen}, se omite")
            continue

        # Nombre versionado: cada version estrena URL, asi el CDN de
        # raw.githubusercontent no sirve un .cs3 viejo con el hash nuevo.
        version = int(entrada.get("version") or 0)
        destino = PUBLICADAS / f"{nombre}-v{version}.cs3"
        shutil.copy2(origen, destino)

        # Borrar artefactos anteriores del mismo plugin
        for viejo in PUBLICADAS.glob("*.cs3"):
            if viejo.name == destino.name:
                continue
            base = viejo.stem
            if base == nombre or base.startswith(nombre + "-v"):
                viejo.unlink()
                print("  version anterior eliminada:", viejo.name)

        entrada["url"] = f"{base_raw}/{destino.name}"
        entrada["repositoryUrl"] = f"https://github.com/{repo_slug()}"
        entrada["fileSize"] = destino.stat().st_size
        entrada["fileHash"] = sha256_archivo(destino)
        entrada["apiVersion"] = entrada.get("apiVersion", 1)
        publicadas.append(entrada)
        print(f"  {nombre:<32} v{entrada.get('version')}  "
              f"{entrada['fileSize'] / 1024:.1f} KiB  {entrada['fileHash'][:23]}...")

    publicadas.sort(key=lambda e: str(e.get("internalName", "")).lower())

    # Limpieza de .cs3 de extensiones que ya no existen
    vigentes = {str(e.get("internalName")) for e in publicadas}
    for f in PUBLICADAS.glob("*.cs3"):
        base = f.stem
        if "-v" in base:
            base = base.rsplit("-v", 1)[0]
        if base not in vigentes:
            f.unlink()
            print("  eliminada:", f.name)

    (PUBLICADAS / "plugins.json").write_text(
        json.dumps(publicadas, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    indice = {
        "repo": f"https://github.com/{repo_slug()}",
        "generado": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "api": "CloudStream 4.9.1",
        "total": len(publicadas),
        "extensiones": [
            {
                "nombre": e.get("name"),
                "interno": e.get("internalName"),
                "version": e.get("version"),
                "status": e.get("status"),
                "autores": e.get("authors", []),
                "descripcion": e.get("description"),
                "idioma": e.get("language"),
                "tipos": e.get("tvTypes", []),
                "bytes": e.get("fileSize"),
                "hash": e.get("fileHash"),
                "url": e.get("url"),
            }
            for e in publicadas
        ],
    }
    (PUBLICADAS / "index.json").write_text(
        json.dumps(indice, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    print(f"Total publicadas: {len(publicadas)}")
    return 0


def main() -> int:
    orden = sys.argv[1] if len(sys.argv) > 1 else ""
    if orden == "preparar":
        return preparar()
    if orden == "publicar":
        return publicar()
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
