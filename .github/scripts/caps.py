#!/usr/bin/env python3
"""Genera las portadas con el numero de capitulo dibujado en la esquina.

La web de AnimeAV1 pinta el "Episodio N" con CSS, asi que ese numero no
existe dentro de la imagen. CloudStream solo puede mostrar una portada por
tarjeta, sin capas encima, de modo que aqui se compone la imagen final:
portada 2:3 + etiqueta con el numero abajo a la derecha.

Salida:
  caps/<id>-<cap>.jpg   portada compuesta
  caps/index.json       {"<id>-<cap>": "titulo", ...} con lo disponible
"""

import io
import json
import os
import re
import sys

import requests
from PIL import Image, ImageDraw, ImageFont

BASE = "https://animeav1.com"
CDN = "https://cdn.animeav1.com"
AQUI = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SALIDA = os.path.join(AQUI, "caps")

ANCHO, ALTO = 300, 450
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"


def sesion() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept-Language": "es-ES,es;q=0.9"})
    return s


def fuente(tam: int) -> ImageFont.FreeTypeFont:
    candidatos = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
    ]
    for ruta in candidatos:
        if os.path.exists(ruta):
            return ImageFont.truetype(ruta, tam)
    return ImageFont.load_default()


def recientes(s: requests.Session) -> list[dict]:
    """Bloque 'Episodios / Recientemente Actualizado' de la portada."""
    html = s.get(BASE + "/", timeout=60).content.decode("utf-8", "replace")
    # Cada tarjeta: enlace /media/<slug>/<cap>, frame thumbnails/<id>.jpg
    # y la etiqueta "Episodio <n>".
    salida, vistos = [], set()
    for bloque in re.findall(r"<article\b.*?</article>", html, re.S):
        m_link = re.search(r'href="(/media/[^"/]+)/(\d+)"', bloque)
        m_img = re.search(r'src="(https://cdn\.animeav1\.com/thumbnails/(\d+)\.jpg)"', bloque)
        if not m_link or not m_img:
            continue
        if 'Portada de' in bloque:  # tarjetas del catalogo, no de episodios
            continue
        m_titulo = re.search(r'<h3[^>]*>(.*?)</h3>', bloque, re.S) or re.search(
            r'<header[^>]*>.*?<div[^>]*>(.*?)</div>', bloque, re.S
        )
        titulo = re.sub(r"<[^>]+>", "", m_titulo.group(1)).strip() if m_titulo else ""
        clave = (m_img.group(2), m_link.group(2))
        if clave in vistos:
            continue
        vistos.add(clave)
        salida.append(
            {
                "id": m_img.group(2),
                "cap": m_link.group(2),
                "titulo": titulo,
                "slug": m_link.group(1),
            }
        )
    return salida


def componer(portada: bytes, cap: str) -> Image.Image:
    img = Image.open(io.BytesIO(portada)).convert("RGB")
    # recorte centrado a 2:3
    objetivo = ANCHO / ALTO
    ancho, alto = img.size
    if ancho / alto > objetivo:
        nuevo = int(alto * objetivo)
        img = img.crop(((ancho - nuevo) // 2, 0, (ancho + nuevo) // 2, alto))
    else:
        nuevo = int(ancho / objetivo)
        img = img.crop((0, (alto - nuevo) // 2, ancho, (alto + nuevo) // 2))
    img = img.resize((ANCHO, ALTO), Image.LANCZOS)

    texto = str(cap)
    font = fuente(34)
    capa = Image.new("RGBA", img.size, (0, 0, 0, 0))
    dibujo = ImageDraw.Draw(capa)
    caja = dibujo.textbbox((0, 0), texto, font=font)
    ancho_t, alto_t = caja[2] - caja[0], caja[3] - caja[1]
    pad_x, pad_y = 11, 5
    ancho_p = ancho_t + pad_x * 2
    alto_p = alto_t + pad_y * 2
    margen = 9
    x0, y0 = ANCHO - margen - ancho_p, ALTO - margen - alto_p
    dibujo.rounded_rectangle(
        (x0, y0, x0 + ancho_p, y0 + alto_p), radius=9, fill=(0, 0, 0, 190)
    )
    dibujo.text(
        (x0 + pad_x - caja[0], y0 + pad_y - caja[1]), texto, font=font, fill=(255, 255, 255, 255)
    )
    return Image.alpha_composite(img.convert("RGBA"), capa).convert("RGB")


def main() -> int:
    s = sesion()
    try:
        items = recientes(s)
    except Exception as e:  # noqa: BLE001
        print(f"no se pudo leer la portada: {e}", file=sys.stderr)
        return 1
    if not items:
        print("sin episodios recientes", file=sys.stderr)
        return 1

    os.makedirs(SALIDA, exist_ok=True)
    indice, creados, fallos = {}, 0, 0
    for it in items:
        nombre = f"{it['id']}-{it['cap']}.jpg"
        indice[f"{it['id']}-{it['cap']}"] = it["titulo"]
        destino = os.path.join(SALIDA, nombre)
        if os.path.exists(destino) and os.path.getsize(destino) > 2000:
            continue
        try:
            portada = s.get(f"{CDN}/covers/{it['id']}.jpg", timeout=45)
            if portada.status_code != 200 or len(portada.content) < 1000:
                raise ValueError(f"portada {portada.status_code}")
            img = componer(portada.content, it["cap"])
            img.save(destino, "JPEG", quality=87, optimize=True)
            creados += 1
            print(f"ok  {nombre}  {it['titulo'][:44]}  cap {it['cap']}")
        except Exception as e:  # noqa: BLE001
            fallos += 1
            indice.pop(f"{it['id']}-{it['cap']}", None)
            print(f"fallo {nombre}: {e}", file=sys.stderr)

    # limpieza: fuera lo que ya no esta en la portada
    for f in os.listdir(SALIDA):
        if f.endswith(".jpg") and f[:-4] not in indice:
            os.remove(os.path.join(SALIDA, f))

    with open(os.path.join(SALIDA, "index.json"), "w", encoding="utf-8") as fh:
        json.dump(indice, fh, ensure_ascii=False, indent=0, sort_keys=True)
    print(f"total={len(indice)} creados={creados} fallos={fallos}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
