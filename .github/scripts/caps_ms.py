#!/usr/bin/env python3
"""Portadas de "Ultimos capitulos" de Monoschinos con el numero dibujado.

La web pinta la etiqueta del episodio con CSS: ese numero no existe dentro de
la imagen, y CloudStream muestra una sola imagen por tarjeta, sin capas. Aqui
se compone la portada (la misma que usa el buscador y el directorio) con una
pastilla "EP n" abajo a la izquierda.

Salida:
  caps/ms/<obra>-<cap>.jpg   portada compuesta
  caps/ms/index.json         {"_diseno": N, "<obra>-<cap>": "titulo", ...}

DISENO tiene que coincidir con CAPS_VER del provider: si cambia el diseno se
rehacen todas las portadas y la URL nueva rompe la cache de imagenes.
"""

import io
import json
import os
import re
import sys
import time

import requests
from PIL import Image, ImageDraw, ImageFont

BASE = "https://monoschinos.st"
AQUI = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SALIDA = os.path.join(AQUI, "caps", "ms")
ANCHO, ALTO = 360, 540
DISENO = 1
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


def retrato(url: str) -> str:
    """El sitio tiene dos imagenes por obra: portada (760x428, apaisada) e
    imagen (420x628, vertical). La vertical es la que usa el buscador y el
    directorio, asi que es la que se compone."""
    m = re.search(r"/portada/(.+?)-portada-(\d+)\.jpg", url)
    if m:
        cola = "?v=6" if "?" in url else ""
        return BASE + "/assets/thumbs/imagen/" + m.group(1) + "-" + m.group(2) + ".jpg.webp" + cola
    return url


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


def portada_ficha(s: requests.Session, episodio: str) -> str | None:
    """La portada buena es la vertical de la ficha: es la misma que muestran el
    buscador y el directorio. No siempre se puede deducir de la fila (hay obras
    con -T1-/-T2- en el nombre), asi que se lee de la ficha del anime."""
    pagina = con_reintentos(s, episodio)
    if not pagina:
        return None
    m = re.search(r'href="(https://monoschinos\.st/anime/[^"]+)"', pagina)
    if not m:
        return None
    ficha = con_reintentos(s, m.group(1))
    if not ficha:
        return None
    imagenes = re.findall(r'(https://monoschinos\.st/assets/thumbs/imagen/[^"]+)', ficha)
    return imagenes[0] if imagenes else None


def recientes(s: requests.Session) -> list[dict]:
    html = s.get(BASE + "/", timeout=60).content.decode("utf-8", "replace")
    # la seccion de "Ultimos capitulos" es la unica con enlaces /ver/ y .card-title
    trozos = re.split(r"(?i)<h2[^>]*>[^<]*ltimos cap", html)
    if len(trozos) < 2:
        return []
    cuerpo = trozos[1]
    salida, vistos = [], set()
    for bloque in re.findall(r"<a\b[^>]*href=\"([^\"]*?/ver/[^\"]+)\"[^>]*>(.*?)</a>", cuerpo, re.S):
        href, interior = bloque
        m_titulo = re.search(r'class="card-title[^"]*"[^>]*>(.*?)<', interior, re.S)
        if not m_titulo:
            continue
        titulo = re.sub(r"<[^>]+>", "", m_titulo.group(1)).strip()
        m_img = re.search(r'data-src="([^"]*?/assets/thumbs/[^"]+)"', interior) or \
                re.search(r'src="([^"]*?/assets/thumbs/[^"]+)"', interior)
        if not titulo or not m_img:
            continue
        m_ep = re.search(r"-episodio-(\d+)", href)
        obra = re.sub(r"-episodio-\d+.*$", "", href.split("/ver/")[-1])
        if obra in vistos:
            continue
        vistos.add(obra)
        salida.append({
            "obra": obra,
            "cap": m_ep.group(1) if m_ep else "1",
            "titulo": titulo,
            "imagen": retrato(m_img.group(1)),
            "episodio": href if href.startswith("http") else BASE + href,
            "url": href,
        })
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
    x0, y0 = margen, ALTO - margen - alto_p          # abajo a la izquierda
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
        print("No se encontraron capitulos recientes", file=sys.stderr)
        return 1
    os.makedirs(SALIDA, exist_ok=True)

    # El sitio a veces corta peticiones: lo que ya estaba hecho no se toca y el
    # indice no se vacia. Las portadas que no se puedan componer guardan aqui su
    # direccion real para que la app nunca enseñe un hueco vacio.
    previo = {}
    ruta_indice = os.path.join(SALIDA, "index.json")
    if os.path.exists(ruta_indice):
        try:
            previo = json.load(open(ruta_indice, encoding="utf-8"))
        except Exception:
            previo = {}

    indice = {"_diseno": DISENO, "_cover": dict(previo.get("_cover", {}))}
    hechos = 0
    for it in items:
        clave = "{}-{}".format(it["obra"], it["cap"])
        destino = os.path.join(SALIDA, clave + ".jpg")
        indice[clave] = it["titulo"]
        if os.path.exists(destino):
            indice["_cover"].pop(clave, None)
            continue
        cover = portada_ficha(s, it["episodio"]) or it["imagen"]
        try:
            r = s.get(cover, timeout=60)
            if r.status_code != 200:
                raise IOError(r.status_code)
            componer(r.content, it["cap"]).save(destino, "JPEG", quality=88)
            indice["_cover"].pop(clave, None)
            hechos += 1
        except Exception as e:  # noqa: BLE001
            print("  sin componer {} ({}): se guarda su portada".format(clave, e), file=sys.stderr)
            indice["_cover"][clave] = cover

    # lo que ya estaba compuesto y sigue en disco se conserva en el indice
    for fichero in os.listdir(SALIDA):
        if not fichero.endswith(".jpg"):
            continue
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
