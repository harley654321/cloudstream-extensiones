# AnimeAV1

Extension de [animeav1.com](https://animeav1.com) (anime en espanol, API 4.9.1).
Carpeta: `extensiones/AnimeAV1/`.

## Como esta hecha

La web es **SvelteKit con render en servidor**: el HTML ya trae el catalogo, la
ficha y los episodios, asi que todo se extrae con Jsoup. Solo hay un endpoint
JSON, `/<ruta>/__data.json`, y se usa para los servidores del episodio. Ese JSON
usa el formato *devalue* de SvelteKit: un array de valores donde cada numero
dentro de un objeto es un indice a ese mismo array, y las hojas son literales.
El provider lo resuelve buscando los objetos `{"server": n, "url": m}`.

## Secciones de portada (listas distintas, sin duplicar)

| Seccion | Origen |
|---|---|
| Recientes | bloque "Episodios / Recientemente actualizado" de la portada |
| Recien agregados | bloque "Animes / Recientemente agregados" de la portada |
| En emision | `/catalogo?status=emision` (65 titulos) |
| Peliculas | `/catalogo?category=pelicula` (602 titulos) |
| Isekai | `/catalogo?genre=isekai` |

Las dos primeras se localizan por el subtitulo del bloque (`p` con el texto) y
luego `closest("section")`, sin depender del orden de la portada.

## Filtros del catalogo (descubiertos del propio sitio)

- `genre=<slug>`: 46 generos (accion, isekai, mecha, romance, terror, ...).
- `category=<slug>`: tv-anime, pelicula, ova, especial, ona.
- `status=emision` / `status=finalizado` / `status=proximamente`.
- `search=<texto>`: busqueda del catalogo.
- `page=N`: 20 resultados por pagina.
- anos 1990-2026 y filtro por letra disponibles en la web.

## Reproduccion

Cada episodio tiene un reproductor propio y mirrors. En `loadLinks`:

1. `iframe[src*=/play/]` -> se convierte a `https://player.zilla-networks.com/m3u8/<hash>`
   (HLS directo, verificado 200 y playlist `#EXTM3U`).
2. Los demas servidores salen del `__data.json` y pasan por `loadExtractor`:
   Voe, MP4Upload, Byse, UPNShare. Mega, 1Fichier y TransferIt se listan pero
   no son reproducibles en la app (son de descarga).

## Verificado

- Portada, busqueda (`naruto` -> 19 resultados), ficha, episodios y HLS probados
  contra el sitio en vivo el 2026-11-10.
- Compilado y publicado por la fabrica en Actions.
