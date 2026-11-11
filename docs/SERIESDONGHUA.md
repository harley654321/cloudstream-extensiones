# Series Donghua (seriesdonghua.com)

Donghua subtitulado en español. Extensión de sitio: **no resuelve reproductores**,
solo consigue las direcciones y las entrega a la lista global de extractores
(los de la app más los de FoxResolvedores).

## Mapa del sitio

| Zona | Dirección | Paginación |
|---|---|---|
| Últimos episodios | `/episodios?page=N` | 648 páginas × 24 |
| En emisión | `/donghuas-en-emision?page=N` | 18 páginas × 24 |
| Finalizados | `/donghuas-finalizados?page=N` | 11 páginas × 24 |
| Géneros (29) | `/accion/?page=N`, `/cultivacion/?page=N`… | sí, con barra final |
| Buscador | `/buscar.php?s=<texto>&page=N` | sí |
| Ficha de serie | `/<slug>/` | todos los episodios en el HTML |
| Episodio | `/<slug>-episodio-<n>/` | — |

Detalles comprobados contra el sitio:

- La última página es la única sin `rel="next"`; la extensión usa eso, y si no
  está, compara el número mayor de la paginación con la página actual.
- `/todos-los-donghuas` devuelve **la misma lista exacta** que
  `/donghuas-en-emision` en sus primeras páginas, por eso no tiene fila propia
  en la portada (sería una fila repetida).
- Las tarjetas de episodio traen imagen, pero se comprobó en cinco series
  (565, 113, 56, 14 y 8 episodios) que es siempre la portada de la serie: el
  sitio no guarda captura por episodio, y la página del episodio tampoco
  (su `og:image` es la portada).
- Los reproductores no están en el HTML: cada pestaña trae `data-video-id` y
  `data-server-index`, y la dirección sale de `POST /api/player/get-server` con
  el token CSRF y la cookie de sesión (sin cookie responde 419).

## Portadas ligeras

El sitio sirve las carátulas a 1280×1795 y ~360 KB. Una fila de 24 tarjetas son
8,6 MB, y por eso las portadas tardaban en aparecer. La extensión pide la misma
imagen reducida a 400 px (500 px en la ficha), lo que baja cada una a ~40 KB
(nueve veces menos) sin tocar nada más. Se controla con `MINIATURAS_LIGERAS` en
el companion del provider: `false` vuelve a la imagen original.

## Servidores

| Servidor | Quién lo resuelve |
|---|---|
| Dailymotion | extractor de la app (`geo.dailymotion.com`) |
| VOE | extractor de la app |
| Filemoon | es Byse por dentro → FoxResolvedores |
| OK.ru | FoxResolvedores (el extractor de la app busca mp4 y OK.ru ya solo da HLS) |
| Rumble | FoxResolvedores |
| StreamSB / likessb | muerto, se salta sin llamarlo |
