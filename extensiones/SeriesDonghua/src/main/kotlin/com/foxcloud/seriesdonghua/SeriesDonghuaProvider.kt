package com.foxcloud.seriesdonghua

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
 * Lo que hay que saber del sitio:
 * - Catalogos con ?page=N y 24 tarjetas por pagina: /todos-los-donghuas,
 *   /donghuas-en-emision, /donghuas-finalizados, /episodios (ultimos capitulos)
 *   y cada genero (/accion?page=N, etc).
 * - Buscador por /buscar.php?s=<texto>. Devuelve series, no episodios sueltos.
 * - Ficha de serie /<slug>/ con la cabecera (titulo, portada, sinopsis, generos,
 *   estado y ano) y TODOS los episodios en el HTML (article.episode-card-item,
 *   atributo data-ep), sin paginar y en orden descendente.
 * - Episodio /<slug>-episodio-<n>/. Los reproductores NO estan en el HTML: cada
 *   pestana trae data-video-id y data-server-index, y la direccion real sale de
 *   un POST a /api/player/get-server con el token CSRF de la pagina y la cookie
 *   de sesion (sin esa cookie el servidor contesta 419).
 *
 * Aqui NO se resuelve ningun reproductor: esta extension solo consigue las
 * direcciones y se las entrega a la lista global de extractores. Dailymotion
 * (geo.dailymotion.com), VOE y Byse los pone la app o FoxResolvedores; OK.ru
 * necesita FoxResolvedores porque el extractor de la app busca una lista de mp4
 * que OK.ru ya no manda. Rumble no se resuelve (Cloudflare lo bloquea).
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

    override val mainPage = mainPageOf(
        "$mainUrl/episodios?page=" to "Ultimos episodios",
        "$mainUrl/donghuas-en-emision?page=" to "En emision",
        "$mainUrl/todos-los-donghuas?page=" to "Todos los donghuas",
        "$mainUrl/donghuas-finalizados?page=" to "Finalizados"
    )

    private companion object {
        const val REINTENTOS = 3

        /**
         * Tope duro para resolver un episodio entero: o sale algo en segundos, o
         * la obra dice que no hay enlaces. Nunca se queda girando.
         */
        const val TOTAL_MS = 18_000L

        /** Servidores que ya no existen: no se les molesta. */
        val MUERTOS = listOf("streambb", "likessb")
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

    private fun portada(tarjeta: Element): String? {
        val img = tarjeta.selectFirst("img") ?: return null
        val src = img.attr("src").ifBlank { img.attr("data-src") }
        if (src.isBlank()) return null
        return absoluto(src)
    }

    // ------------------------------------------------------------ portada

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = documento(request.data) ?: return newHomePageResponse(request.name, emptyList())

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
            val episodio = tarjeta.selectFirst(".badge-ep")?.text()?.trim()

            // La fila de ultimos episodios enlaza al capitulo; se mantiene asi
            // para poder abrirlo de una vez, con su etiqueta EP.
            val nombre = if (destino.contains("-episodio-") && episodio != null) {
                "$titulo - $episodio"
            } else titulo

            salida.add(newAnimeSearchResponse(nombre, destino, TvType.Anime) { this.posterUrl = poster })
        }

        val paginas = doc.select("a[href*=page=]").mapNotNull { enlace ->
            Regex("""[?&]page=(\d+)""").find(enlace.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }
        val hayMas = paginas.any { it > page }

        return newHomePageResponse(request.name, salida, hayMas)
    }

    // ----------------------------------------------------------- buscador

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = documento("$mainUrl/buscar.php?s=${URLEncoder.encode(query, "UTF-8")}")
            ?: return emptyList()

        val salida = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()
        doc.select("article.donghua-card").forEach { tarjeta ->
            var destino = tarjeta.selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() }
                ?.let { absoluto(it) } ?: return@forEach
            var titulo = tarjeta.selectFirst(".card-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEach

            // En el buscador solo van obras: si llega un capitulo se queda la serie.
            if (destino.contains("-episodio-")) {
                destino = destino.substringBefore("-episodio-") + "/"
                titulo = titulo
                    .replace(Regex("""\s*Episodio\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
                    .trim().ifEmpty { titulo }
            }
            if (!vistos.add(destino)) return@forEach

            val poster = portada(tarjeta)
            salida.add(newAnimeSearchResponse(titulo, destino, TvType.Anime) { this.posterUrl = poster })
        }
        return salida
    }

    // -------------------------------------------------------------- ficha

    private fun datoTecnico(doc: Document, etiqueta: String): String? =
        doc.select(".tech-info-item").firstOrNull { item ->
            item.selectFirst(".tech-info-label")?.text()?.contains(etiqueta, true) == true
        }?.selectFirst(".tech-info-val")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    override suspend fun load(url: String): LoadResponse? {
        val doc = documento(url) ?: return null

        val titulo = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val sinopsis = doc.selectFirst("h2:contains(Sinopsis)")?.nextElementSibling()?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
        val generos = doc.select(".genre-pill").map { it.text().trim() }.filter { it.isNotEmpty() }
        val estado = datoTecnico(doc, "Estado")
        val anio = datoTecnico(doc, "Año")?.toIntOrNull() ?: datoTecnico(doc, "Estreno")?.toIntOrNull()

        // Ficha de un solo episodio (fila de ultimos capitulos): se abre directo.
        if (url.contains("-episodio-")) {
            return newTvSeriesLoadResponse(titulo, url, TvType.Anime) {
                this.posterUrl = poster
                this.plot = sinopsis
                this.tags = generos
                this.year = anio
                this.episodes = listOf(
                    newEpisode(url) {
                        this.name = titulo
                        this.posterUrl = poster
                    }
                )
            }
        }

        val episodios = doc.select("article.episode-card-item").mapNotNull { tarjeta ->
            val enlace = tarjeta.selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val destino = absoluto(enlace)
            val numero = tarjeta.attr("data-ep").toIntOrNull()
                ?: Regex("""-episodio-(\d+)""").find(destino)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(destino) {
                this.name = if (numero != null) "Episodio $numero" else "Episodio"
                this.episode = numero
                this.posterUrl = poster
            }
        }.sortedBy { it.episode ?: 0 }

        return newTvSeriesLoadResponse(titulo, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = sinopsis
            this.tags = generos
            this.year = anio
            this.episodes = episodios
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
