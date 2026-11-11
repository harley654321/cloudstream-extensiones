# DonghuaLife (donghualife.com)

Donghua subtitulado en español. Extensión **v1** de la fábrica.

## Cómo está hecho el sitio

Es una web **Next.js**. Todo lo que necesita la extensión llega ya dibujado en el
HTML (tarjetas y enlaces normales), así que no hace falta navegador ni
JavaScript: se pide la página y se lee.

## Filas de la portada

| Fila | Dirección | Páginas | Notas |
|---|---|---|---|
| Últimos capítulos | `/ultimos-episodios` | 1 | 88 capítulos por página |
| Series | `/series?page=N` | 15 | 24 por página |
| Películas | `/peliculas?page=N` | 2 | 24 por página |
| Tendencias | `/rankings` | 1 | 10 obras |

Comprobado que `/ultimos-episodios` **ignora el parámetro de página**: con
`?page=2`, `?page=3`, `?pagina=2`, `?p=2`, `?offset=88` y `?page=2&limit=88`
devuelve siempre los mismos 88 capítulos. Por eso esa fila tiene una sola página
(si se pidiera la 2 se repetiría la 1, que es justo el fallo que se corrigió en
Series Donghua).

`/genres` solo tiene cinco géneros y cada uno con una serie, así que no merece
fila propia. `/vip` no lista reproducciones (es informativa de la cuenta).

El final de la paginación se detecta con la propia paginación del sitio: si no
ofrece un número mayor que el actual, no hay página siguiente (`/series?page=15`
y `/peliculas?page=2` lo confirman).

## Buscador

- Series: `/series?q=<texto>`
- Películas: `/peliculas?q=<texto>`

Devuelve obras, no capítulos sueltos (comprobado con `doupo` y `rey`).

## Ficha de serie

`/series/<slug>` trae el título, la sinopsis (`og:description`), los géneros y la
lista **completa** de temporadas en el HTML, pero solo dibuja los capítulos de la
primera. Las demás se piden a
`/api/series/<slug>/seasons/<temporada>/episodes`, que devuelve JSON con `id`,
`title`, `number`, `image` e `isVipOnly`.

La dirección de cada capítulo se arma como `/watch/<temporada>-<número>`.
Verificado en Doupo Cangqiong (temporadas 2, 7 y 8, el especial y "The Origin").

Ejemplo real: Doupo Cangqiong tiene **13 temporadas** (incluye especiales y películas)
y su temporada 8 tiene **61 capítulos**; Against The Sky Supreme tiene **565**
capítulos en una sola temporada.

## Reproductores

Cada página de capítulo o de película lleva dentro un arreglo `sources` con una
entrada por servidor y un **token cifrado**. La dirección real sale de un `POST`
a `/api/player/source` con ese token:

- Si la respuesta es una ruta propia (`/api/r2-media?u=…`), es el **master m3u8**
  del sitio (hasta 4K) y se entrega a la app como HLS.
- Si es una dirección absoluta, es un embed y va a la lista global de
  extractores.

El m3u8 se pide sin galletas ni token de sesión y responde 200, así que se
reproduce y se descarga igual que en las demás extensiones de la fábrica.

Servidores vistos: **R2** (HLS propio), **Rumble**, **Dailymotion**, **Odysee** y
**Ok.ru**. Los capítulos recientes suelen traer solo Rumble/Dailymotion/Odysee;
los que tienen R2 van por su HLS.

Esta extensión **no resuelve nada**: solo consigue direcciones y las entrega.
Rumble y OK.ru los resuelve **FoxResolvedores**; Dailymotion y el HLS los resuelve
la app.

## Subtítulos

`/api/subtitles?episodeId=<id>` da la lista ("Español (Estilos)") y
`/api/subtitles/<id>` devuelve el `.ass` **envuelto en JSON**. No existe ruta que
sirva el archivo crudo (`?raw=1`, `.ass` y `/file` dan 404 o el mismo JSON), así
que hoy no se pueden entregar como pista externa sin romper la app.

## Portadas

Se piden **tal cual las sirve el sitio**, igual que en el resto de la fábrica: ni
intermediarios ni reducciones. En los catálogos la dirección real viene dentro
del optimizador de Next (`/_next/image?url=%2Fuploads%2F…&w=…`), así que se
extrae la ruta original y se pide limpia.
