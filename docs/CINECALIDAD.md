# Cinecalidad

Extension de [cinecalidad.am](https://www.cinecalidad.am) (peliculas y series en latino, API 4.9.1).
Carpeta: `extensiones/Cinecalidad/`.

## Estructura del sitio (WordPress con plantilla propia, base Dooplay)

| Que | Ruta |
|---|---|
| Ficha de pelicula | `/ver-pelicula/<slug>/` |
| Ficha de serie | `/ver-serie/<slug>/` |
| Episodio | `/ver-el-episodio/<slug>/` |
| Archivo de series | `/ver-serie/` |
| Estrenos por anio | `/fecha-de-lanzamiento/<anio>/` |
| Genero | `/genero-de-la-pelicula/<slug>/` |
| Busqueda | `/?s=<texto>` |
| Paginacion | `/page/N/` al final de cualquier ruta |

Las tarjetas son `article.item` con el poster en `img[data-src]` (TMDB) y el titulo
en el `alt` de la imagen. Los bloques publicitarios tambien usan `article.item`,
pero enlazan a `#base64`, asi que se descartan exigiendo que el `href` case con
`/(ver-pelicula|ver-serie)/<slug>/`.

## Paginacion real (medida el 2026-11-10)

El sitio marca la paginacion con `.wp-pagenavi` y el texto "Pagina X de Y":

- Peliculas: **871 paginas** (12 fichas por pagina)
- Series: **83 paginas** (16 por pagina)
- Estrenos: **38 paginas** (14-16 por pagina)
- Accion: **146 paginas**
- Animacion: similar, sin solapes entre pagina 1 y 2

`hasNext` se calcula leyendo el mayor numero de pagina enlazado en `.wp-pagenavi`
y comparandolo con la pagina actual, asi que al deslizar la app pide la siguiente
y se detiene sola en la ultima.

## Ficha

- `#single h1` titulo, `img[data-src]` portada, primer `td p` largo = sinopsis
- generos: `a[href*='/genero-de-la-pelicula/']`
- nota TMDB: `#star-rating-box` + `b` hermano (escala 10)
- series: `#seasons .se-c ul.episodios li` con `.numerando` (`S1-E1`) y
  `.episodiotitle a`; la miniatura del capitulo sale de `.imagen img[data-src]`

## Reproduccion

Unico host: **Vimeos** (familia StreamWish). Los servidores estan en
`#playeroptionsul li.dooplay_player_option[data-option]` como URL de embed
(se descarta la opcion `_trailer`).

El embed trae un packer JS; al descomprimirlo aparece
`sources:[{file:"https://sNN.vimeos.net/hls2/.../master.m3u8?..."}]`.

Cadena comprobada de punta a punta el 2026-11-10:

- master.m3u8 -> 200, 2 variantes (480p y 720p)
- variante -> 200 con 995 segmentos (Dune 2) y 258 (Chicago PD 1x1)
- primer segmento -> 200 `video/MP2T`

El token del master dura 12 h (`e=43200`) y se genera al abrir el episodio, asi
que no hay enlaces caducados guardados.
