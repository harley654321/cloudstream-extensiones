#!/usr/bin/env python3
"""Portadas de "Ultimos episodios" de Mundo Donghua con el numero dibujado.

La web escribe "Episodio 164" en el titulo de la tarjeta; el numero no existe
dentro de la imagen y CloudStream muestra una sola imagen por tarjeta, sin
capas. Aqui se compone la portada vertical de la obra con una pastilla
"EP n" abajo a la izquierda, igual que en Monoschinos y AnimeAV1.

Salida:
  caps/mdh/<obra>-<cap>.jpg   portada compuesta
  caps/mdh/index.json         {"_diseno": N, "<obra>-<cap>": "titulo", "_cover": {...}}

DISENO tiene que coincidir con CAPS_VER del provider.
"""

import io
import json
import os
import re
import sys
import time

import requests
from PIL import Image, ImageDraw, ImageFont

BASE = "https://www.mundodonghua.com"
AQUI = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SALIDA = os.path.join(AQUI, "caps", "mdh")
ANCHO, ALTO = 360, 540
DISENO = 1
PAGINAS = 12
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")


def sesion() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept": "*/*", "Referer": BASE + "/"})
    return s


def fuente(tam: int) -> ImageFont.FreeTypeFont:
    for ruta in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
    ):
        if os.path.exists(ruta):
            return ImageFont.truetype(ruta, tam)
    return ImageFont.load_default()


def con_reintentos(s: requests.Session, url: str, intentos: int = 3) -> str | None:
    for i in range(intentos):
        try:
            r = s.get(url, timeout=60)
            if r.status_code == 200:
                return r.text
        except Exception:  # noqa: BLE001
            pass
        time.sleep(0.6 * (i + 1))
    return None


def recientes(s: requests.Session) -> list[dict]:
    """Tarjetas de /lista-episodios: enlace /ver/<obra>/<cap>, titulo y portada."""
    vistos, salida = set(), []
    for pagina in range(1, PAGINAS + 1):
        html = con_reintentos(s, "{}/lista-episodios?page={}".format(BASE, pagina))
        if not html:
            continue
        for bloque in re.findall(r'<a href="([^"]*/ver/[^"]+)"[^>]*>(.*?)</a>', html, re.S):
            href, cuerpo = bloque
            m = re.search(r"/ver/([a-z0-9-]+)/(\d+)", href)
            if not m:
                continue
            obra, cap = m.group(1), m.group(2)
            clave = "{}-{}".format(obra, cap)
            if clave in vistos:
                continue
            img = re.search(r'<img[^>]+src="([^"]+)"', cuerpo)
            titulo = re.search(r'class="md-card-title"[^>]*>(.*?)</', cuerpo, re.S)
            if not img:
                continue
            imagen = img.group(1)
            if imagen.startswith("//"):
                imagen = "https:" + imagen
            elif imagen.startswith("/"):
                imagen = BASE + imagen
            texto = re.sub(r"<[^>]+>", "", titulo.group(1)).strip() if titulo else clave
            vistos.add(clave)
            salida.append({"clave": clave, "cap": cap, "titulo": texto, "imagen": imagen})
    return salida


def componer(portada: bytes, cap: str) -> Image.Image:
    img = Image.open(io.BytesIO(portada)).convert("RGB")
    objetivo = ANCHO / ALTO
    ancho, alto = img.size
    if ancho / alto > objetivo:
        nuevo = int(alto * objetivo)
        img = img.crop(((ancho - nuevo) // 2, 0, (ancho + nuevo) // 2, alto))
    else:
        nuevo = int(ancho / objetivo)
        img = img.crop((0, (alto - nuevo) // 2, ancho, (alto + nuevo) // 2))
    img = img.resize((ANCHO, ALTO), Image.LANCZOS)

    texto = "EP " + str(cap)
    font = fuente(34)
    medidor = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    caja = medidor.textbbox((0, 0), texto, font=font)
    ancho_t, alto_t = caja[2] - caja[0], caja[3] - caja[1]
    pad_x, pad_y = 16, 9
    ancho_p, alto_p = ancho_t + pad_x * 2, alto_t + pad_y * 2
    margen = 18
    x0, y0 = margen, ALTO - margen - alto_p
    radio = 13

    capa = Image.new("RGBA", img.size, (0, 0, 0, 0))
    dibujo = ImageDraw.Draw(capa)
    dibujo.rounded_rectangle((x0, y0 + 3, x0 + ancho_p, y0 + alto_p + 3),
                             radius=radio, fill=(0, 0, 0, 110))
    dibujo.rounded_rectangle((x0, y0, x0 + ancho_p, y0 + alto_p),
                             radius=radio, fill=(0, 0, 0, 190),
                             outline=(255, 255, 255, 70), width=2)
    dibujo.text((x0 + pad_x - caja[0], y0 + pad_y - caja[1]), texto,
                font=font, fill=(255, 255, 255, 255))
    return Image.alpha_composite(img.convert("RGBA"), capa).convert("RGB")


def main() -> int:
    s = sesion()
    items = recientes(s)
    if not items:
        print("No se encontraron episodios recientes", file=sys.stderr)
        return 1
    os.makedirs(SALIDA, exist_ok=True)

    ruta_indice = os.path.join(SALIDA, "index.json")
    previo = {}
    if os.path.exists(ruta_indice):
        try:
            previo = json.load(open(ruta_indice, encoding="utf-8"))
        except Exception:  # noqa: BLE001
            previo = {}

    indice = {"_diseno": DISENO, "_cover": dict(previo.get("_cover", {}))}
    hechos = 0
    for it in items:
        clave = it["clave"]
        destino = os.path.join(SALIDA, clave + ".jpg")
        indice[clave] = it["titulo"]
        if os.path.exists(destino):
            indice["_cover"].pop(clave, None)
            continue
        try:
            r = s.get(it["imagen"], timeout=60)
            if r.status_code != 200:
                raise IOError(r.status_code)
            componer(r.content, it["cap"]).save(destino, "JPEG", quality=88)
            indice["_cover"].pop(clave, None)
            hechos += 1
        except Exception as e:  # noqa: BLE001
            print("  sin componer {} ({}): se guarda su portada".format(clave, e), file=sys.stderr)
            indice["_cover"][clave] = it["imagen"]

    for fichero in os.listdir(SALIDA):
        if fichero.endswith(".jpg"):
            clave = fichero[:-4]
            if clave not in indice:
                indice[clave] = previo.get(clave, "")

    with open(ruta_indice, "w", encoding="utf-8") as f:
        json.dump(indice, f, ensure_ascii=False, indent=1, sort_keys=True)
    print("Portadas: {} nuevas, {} compuestas, {} de respaldo".format(
        hechos, len(indice) - 2 - len(indice["_cover"]), len(indice["_cover"])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
