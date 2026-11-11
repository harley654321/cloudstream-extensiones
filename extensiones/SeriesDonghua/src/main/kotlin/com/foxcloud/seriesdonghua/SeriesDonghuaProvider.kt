package com.foxcloud.seriesdonghua

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Series Donghua (seriesdonghua.com): donghua subtitulado en espanol.
 *
 * Lo que hay que saber del sitio (comprobado navegando la web entera):
 * - Portada: destacados, "Nuevos Episodios" (su "Ver Todos" lleva a /episodios)
 *   y "Series Donghua en Emision" (su "Ver Mas" lleva a /donghuas-en-emision).
 *   La barra lateral trae populares, programacion semanal y 29 generos.
 * - TODOS los catalogos paginan de verdad con ?page=N y 24 tarjetas por pagina:
 *   /episodios (648 paginas), /donghuas-en-emision (18), /donghuas-finalizados
 *   (11) y cada genero con barra final (/accion/?page=N, /cultivacion/?page=N...).
 *   La ultima pagina es la unica que no lleva rel="next".
 * - /todos-los-donghuas devuelve EXACTAMENTE la misma lista que
 *   /donghuas-en-emision en sus primeras paginas, por eso no tiene fila propia:
 *   seria una fila repetida en la portada.
 * - Buscador por /buscar.php?s=<texto>, tambien paginado con &page=N.
 *   Devuelve series, no episodios sueltos.
 * - Ficha de serie /<slug>/ con cabecera (titulo, portada, sinopsis, generos,
 *   estado, ano) y TODOS los episodios en el HTML (article.episode-card-item,
 *   atributo data-ep), sin paginar y de mas nuevo a mas viejo.
 * - Cada tarjeta de episodio trae una imagen, pero se comprobo en cinco series
 *   (565, 113, 56, 14 y 8 episodios) que es SIEMPRE la misma portada de la
 *   serie: el sitio no guarda captura por episodio. La pagina del episodio
 *   tampoco (su og:image es la portada de la serie).
 * - Episodio /<slug>-episodio-<n>/. Los reproductores NO estan en el HTML: cada
 *   pestana trae data-video-id y data-server-index, y la direccion real sale de
 *   un POST a /api/player/get-server con el token CSRF de la pagina y la cookie
 *   de sesion (sin esa cookie el servidor contesta 419).
 *
 * Aqui NO se resuelve ningun reproductor: esta extension solo consigue las
 * direcciones y se las entrega a la lista global de extractores. Dailymotion
 * (geo.dailymotion.com), VOE y Byse los pone la app o FoxResolvedores; OK.ru
 * necesita FoxResolvedores porque el extractor de la app busca una lista de mp4
 * que OK.ru ya no manda. Rumble tambien lo resuelve FoxResolvedores.
 */
class SeriesDonghuaProvider : MainAPI() {
    override var mainUrl = "https://seriesdonghua.com"
    override var name = "Series Donghua"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cabeceras = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml",
        "Accept-Language" to "es-ES,es;q=0.9"
    )

    /**
     * Filas de la portada. Las tres primeras son los catalogos que el propio
     * sitio enlaza desde su inicio ("Ver Todos" y "Ver Mas") y las de genero
     * salen de su barra lateral. Todas paginan con ?page=N.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/episodios?page=" to "Últimos episodios",
        "$mainUrl/donghuas-en-emision?page=" to "En emisión",
        "$mainUrl/donghuas-finalizados?page=" to "Finalizados",
        "$mainUrl/accion/?page=" to "Acción",
        "$mainUrl/artes-marciales/?page=" to "Artes Marciales",
        "$mainUrl/cultivacion/?page=" to "Cultivación"
    )

    private companion object {
        const val REINTENTOS = 3

        /**
         * Tope duro para resolver un episodio entero: o sale algo en segundos, o
         * la obra dice que no hay enlaces. Nunca se queda girando.
         */
        const val TOTAL_MS = 18_000L

        /** Tarjetas por pagina del sitio: si vienen todas, hay pagina siguiente. */
        const val TARJETAS_POR_PAGINA = 24

        /**
         * Portadas ligeras. El sitio sirve las caratulas a 1280x1795 y ~360 KB
         * cada una: una fila de 24 son 8,6 MB y por eso tardaban en aparecer.
         * Reducidas a 400 px quedan en ~40 KB (nueve veces menos). Si algun dia
         * se quiere volver a la imagen original basta con poner esto en false.
         */
        const val MINIATURAS_LIGERAS = true
        const val PROXY_IMAGENES = "https://wsrv.nl/"

        /** Servidores que ya no existen: no se les molesta. */
        val MUERTOS = listOf("streambb", "streamsb", "likessb", "sbthe", "sbplay")
    }

    // ---------------------------------------------------------------- red

    private suspend fun pedir(
        url: String,
        referer: String? = null,
        intentos: Int = REINTENTOS
    ): String? {
        repeat(intentos) { intento ->
            runCatching { return app.get(url, headers = cabeceras, referer = referer).text }
            if (intento < intentos - 1) Thread.sleep(500L * (intento + 1))
        }
        return null
    }

    private suspend fun documento(url: String, referer: String? = null): Document? {
        val texto = pedir(url, referer) ?: return null
        return runCatching { Jsoup.parse(texto, url) }.getOrNull()
    }

    private fun absoluto(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> mainUrl + "/" + url.trimStart('/')
    }

    /**
     * Reduce la portada antes de mandarla a la app. Solo se toca lo que sirve el
     * propio sitio; cualquier otra direccion (por ejemplo un alojador externo)
     * se entrega tal cual.
     */
    private fun miniatura(url: String?, ancho: Int = 400): String? {
        if (url.isNullOrBlank()) return null
        if (!MINIATURAS_LIGERAS) return url
        if (!url.contains("seriesdonghua.com")) return url
        val limpio = url.substringAfter("://")
        val codificado = URLEncoder.encode(limpio, "UTF-8")
        return "$PROXY_IMAGENES?url=$codificado&w=$ancho&output=webp&q=78"
    }

    private fun portada(tarjeta: Element, ancho: Int = 400): String? {
        val img = tarjeta.selectFirst("img") ?: return null
        val src = img.attr("src").ifBlank { img.attr("data-src") }
        if (src.isBlank()) return null
        return miniatura(absoluto(src), ancho)
    }

    // ------------------------------------------------------------ portada

    /** Convierte las tarjetas de una pagina de catalogo en resultados. */
    private fun tarjetas(doc: Document): List<SearchResponse> {
        val salida = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()

        doc.select("article.donghua-card").forEach { tarjeta ->
            val enlace = tarjeta.selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val destino = absoluto(enlace)
            if (!vistos.add(destino)) return@forEach

            val titulo = tarjeta.selectFirst(".card-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            val poster = portada(tarjeta)
            val episodio = tarjeta.selectFirst(".badge-ep")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: tarjeta.attr("data-ep").takeIf { it.isNotBlank() }?.let { "EP $it" }

            // La fila de ultimos episodios enlaza al capitulo; se mantiene asi
            // para poder abrirlo de una vez, con su etiqueta EP.
            val nombre = if (destino.contains("-episodio-") && episodio != null) {
                "$titulo - $episodio"
            } else titulo

            salida.add(newAnimeSearchResponse(nombre, destino, TvType.Anime) { this.posterUrl = poster })
        }
        return salida
    }

    /**
     * Hay pagina siguiente si el sitio marca rel="next" o si en la paginacion
     * aparece un numero mayor que el actual. En la ultima pagina no hay ninguna
     * de las dos cosas (comprobado en /episodios?page=648).
     */
    private fun haySiguiente(doc: Document, page: Int): Boolean {
        if (doc.selectFirst("a[href][rel=next]") != null) return true
        val mayor = doc.select("a[href]").mapNotNull { enlace ->
            Regex("""[?&]page=(\d+)""").find(enlace.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return mayor > page
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Antes se pedia la direccion sin el numero, asi que la pagina 2 devolvia
        // otra vez la 1 y la app no podia avanzar nunca.
        val base = request.data
        val url = if (base.contains("?page=")) "$base$page" else base

        val doc = documento(url) ?: return newHomePageResponse(request, emptyList(), false)
        val items = tarjetas(doc)
        return newHomePageResponse(request, items, items.isNotEmpty() && haySiguiente(doc, page))
    }

    // ----------------------------------------------------------- buscador

    override suspend fun search(query: String): List<SearchResponse> {
        val buscado = URLEncoder.encode(query, "UTF-8")
        val salida = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()

        fun volcar(doc: Document) {
            // Tarjetas repetidas (misma obra en dos episodios) se quedan en una.
            val bloque = tarjetas(doc).groupBy { it.url }.values.map { it.first() }
            for (resultado in bloque) {
                var destino = resultado.url
                var titulo = resultado.name

                // En el buscador solo van obras: si llega un capitulo se queda la serie.
                if (destino.contains("-episodio-")) {
                    destino = destino.substringBefore("-episodio-") + "/"
                    titulo = titulo
                        .replace(Regex("""\s*-\s*EP\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("""\s*Episodio\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
                        .trim().ifEmpty { resultado.name }
                }
                if (!vistos.add(destino)) continue

                salida.add(newAnimeSearchResponse(titulo, destino, TvType.Anime) {
                    this.posterUrl = resultado.posterUrl
                })
            }
        }

        val primera = documento("$mainUrl/buscar.php?s=$buscado") ?: return emptyList()
        volcar(primera)

        // El buscador tambien pagina con &page=N. Solo se pide la segunda si la
        // primera vino llena, para no meter una peticion de mas en cada busqueda.
        if (tarjetas(primera).size >= TARJETAS_POR_PAGINA) {
            documento("$mainUrl/buscar.php?s=$buscado&page=2")?.let { volcar(it) }
        }
        return salida
    }

    // -------------------------------------------------------------- ficha

    private fun datoTecnico(doc: Document, etiqueta: String): String? =
        doc.select(".tech-info-item").firstOrNull { item ->
            item.selectFirst(".tech-info-label")?.text()?.contains(etiqueta, true) == true
        }?.selectFirst(".tech-info-val")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    private fun episodios(doc: Document, portadaSerie: String?): List<Episode> {
        val vistos = mutableSetOf<String>()
        return doc.select("article.episode-card-item").mapNotNull { tarjeta ->
            val enlace = tarjeta.selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val destino = absoluto(enlace)
            if (!vistos.add(destino)) return@mapNotNull null

            val numero = tarjeta.attr("data-ep").toIntOrNull()
                ?: Regex("""-episodio-(\d+)""").find(destino)?.groupValues?.get(1)?.toIntOrNull()

            // La tarjeta trae imagen propia; si no la trae, la portada de la serie.
            val imagen = portada(tarjeta) ?: portadaSerie

            newEpisode(destino) {
                this.name = if (numero != null) "Episodio $numero" else "Episodio"
                this.episode = numero
                this.posterUrl = imagen
            }
        }.sortedBy { it.episode ?: 0 }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = documento(url) ?: return null

        val titulo = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = miniatura(
            doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() },
            500
        )
        val sinopsis = doc.selectFirst("h2:contains(Sinopsis)")?.nextElementSibling()?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
        val generos = doc.select(".genre-pill").map { it.text().trim() }.filter { it.isNotEmpty() }
        val anio = datoTecnico(doc, "Año")?.toIntOrNull() ?: datoTecnico(doc, "Estreno")?.toIntOrNull()

        // Ficha de un solo episodio (fila de ultimos capitulos): se abre directo.
        if (url.contains("-episodio-")) {
            val numero = Regex("""-episodio-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            val unico = listOf(
                newEpisode(url) {
                    this.name = if (numero != null) "Episodio $numero" else titulo
                    this.episode = numero
                    this.posterUrl = poster
                }
            )
            return newTvSeriesLoadResponse(titulo, url, TvType.Anime, unico) {
                this.posterUrl = poster
                this.plot = sinopsis
                this.tags = generos
                this.year = anio
            }
        }

        val lista = episodios(doc, poster)

        return newTvSeriesLoadResponse(titulo, url, TvType.Anime, lista) {
            this.posterUrl = poster
            this.plot = sinopsis
            this.tags = generos
            this.year = anio
        }
    }

    // ------------------------------------------------------------ enlaces

    /**
     * No se resuelve nada aqui. Se pide al sitio la direccion de cada pestana de
     * reproductor y se entrega a la lista global de extractores, todas a la vez
     * y con tope de tiempo.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pagina = runCatching { app.get(data, headers = cabeceras) }.getOrNull() ?: return false
        val html = pagina.text
        val galletas = pagina.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        val token = Regex("""name="csrf-token"\s+content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return false
        val videoId = Regex("""data-video-id="(\d+)"""").find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: return false

        val pestanas = runCatching {
            Jsoup.parse(html, data).select(".server-tab-btn").mapNotNull { boton ->
                val indice = boton.attr("data-server-index").toIntOrNull() ?: return@mapNotNull null
                val nombre = boton.selectFirst("span")?.text()?.trim() ?: ""
                indice to nombre
            }
        }.getOrDefault(emptyList())
        if (pestanas.isEmpty()) return false

        val encontrado = AtomicBoolean(false)
        val embeds = Collections.synchronizedSet(mutableSetOf<String>())
        val emitidos = Collections.synchronizedSet(mutableSetOf<String>())

        withTimeoutOrNull(TOTAL_MS) {
            coroutineScope {
                pestanas.map { (indice, nombre) ->
                    async {
                        if (MUERTOS.any { nombre.contains(it, true) }) return@async
                        val embed = pedirEmbed(data, galletas, token, videoId, indice) ?: return@async
                        if (!embeds.add(embed)) return@async
                        runCatching {
                            loadExtractor(embed, data, subtitleCallback) { enlace ->
                                if (emitidos.add(enlace.url.trim())) {
                                    encontrado.set(true)
                                    callback(enlace)
                                }
                            }
                        }
                    }
                }.forEach { it.await() }
            }
        }
        return encontrado.get()
    }

    /** POST al sitio con el token y la cookie de la propia pagina del episodio. */
    private suspend fun pedirEmbed(
        pagina: String,
        galletas: String,
        token: String,
        videoId: Int,
        indice: Int
    ): String? {
        if (galletas.isBlank()) return null
        val cuerpo = runCatching {
            app.post(
                "$mainUrl/api/player/get-server",
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "Content-Type" to "application/json",
                    "X-CSRF-TOKEN" to token,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to pagina,
                    "Cookie" to galletas
                ),
                json = mapOf("video_id" to videoId, "server_index" to indice)
            ).text
        }.getOrNull() ?: return null

        val embed = Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(cuerpo)?.groupValues?.get(1)
            ?: return null
        return embed.replace("\\/", "/").trim().takeIf { it.startsWith("http") }
    }
}
