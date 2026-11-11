package com.foxcloud.cinecalidad

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

/**
 * Cinecalidad (cinecalidad.am) - peliculas y series en latino.
 *
 * WordPress con plantilla propia (base Dooplay). Lo importante del sitio:
 *  - fichas: /ver-pelicula/<slug>/ y /ver-serie/<slug>/
 *  - episodios: /ver-el-episodio/<slug>/
 *  - generos: /genero-de-la-pelicula/<slug>/, estrenos: /fecha-de-lanzamiento/<anio>/
 *  - paginacion: /page/N/ al final de cada ruta
 *  - servidores: #playeroptionsul li.dooplay_player_option[data-option] (URL del embed)
 *
 * El unico host de video es Vimeos (familia StreamWish): su embed lleva un packer
 * con la lista `sources:[{file:"...master.m3u8"}]`, que se saca con getAndUnpack.
 */
class CinecalidadProvider : MainAPI() {
    override var mainUrl = "https://www.cinecalidad.am"
    override var name = "Cinecalidad"
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false

    override val mainPage = mainPageOf(
        "movies" to "Peliculas",
        "series" to "Series",
        "estrenos" to "Estrenos 2026",
        "accion" to "Accion",
        "animacion" to "Animacion"
    )

    private val paginaRegex = Regex("""/page/(\d+)/?""")

    private val fichaRegex = Regex("""/(ver-pelicula|ver-serie)/[a-z0-9-]+/?$""")
    private fun anioActual(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    private val m3u8Regex = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")

    // ---------------------------------------------------------------- portada

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // La app pide "la pagina N de la fila"; aqui N son lotes de paginas del sitio,
        // asi la fila llega larga de una vez y al deslizar sigue cargando sin cortarse.
        val porLote = 10
        val desde = (page - 1) * porLote + 1
        val hasta = desde + porLote - 1

        // En paralelo: 10 paginas del sitio por peticion de fila, asi la fila
        // llega larga de una vez (~120-160 fichas) sin depender de que la app
        // pida la siguiente pagina.
        val documentos = (desde..hasta).toList().amap { p ->
            runCatching { app.get(listUrl(request.data, p)).document }.getOrNull()
        }.filterNotNull()

        val items = documentos.flatMap { it.cards() }.distinctBy { it.url }
        val ultima = documentos.mapNotNull { maxPagina(it) }.maxOrNull() ?: hasta
        return newHomePageResponse(request.name, items, ultima > hasta)
    }

    private fun maxPagina(doc: Document): Int? =
        doc.select(".wp-pagenavi a[href], .pagination a[href]")
            .mapNotNull { a -> paginaRegex.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()

    private fun listUrl(kind: String, page: Int): String {
        val base = when (kind) {
            "movies" -> "$mainUrl/"
            "series" -> "$mainUrl/ver-serie/"
            "estrenos" -> "$mainUrl/fecha-de-lanzamiento/${anioActual()}/"
            else -> "$mainUrl/genero-de-la-pelicula/$kind/"
        }
        // Sin plantillas para no arriesgar el escapado: se concatena a mano.
        return if (page <= 1) base else base.trimEnd('/') + "/page/" + page + "/"
    }

    // ---------------------------------------------------------------- busqueda

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(mainUrl, params = mapOf("s" to query)).document
        return doc.cards()
    }

    // ------------------------------------------------------------------ ficha

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val single = doc.selectFirst("#single") ?: doc
        val titulo = single.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = single.selectFirst("img[data-src]")?.attr("data-src")
            ?: single.selectFirst("img[src^='https://image']")?.attr("src")
        val sinopsis = single.select("td p").map { it.text().trim() }
            .firstOrNull { it.length > 60 } ?: single.select("p").map { it.text().trim() }
            .firstOrNull { it.length > 60 }
        val generos = single.select("a[href*='/genero-de-la-pelicula/']").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()
        val puntos = doc.selectFirst("#star-rating-box")?.parent()?.selectFirst("b")
            ?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull()

        val episodios = mutableListOf<com.lagradost.cloudstream3.Episode>()
        doc.select("#seasons .se-c ul.episodios li").forEach { li ->
            val enlace = li.selectFirst(".episodiotitle a") ?: return@forEach
            val numerando = li.selectFirst(".numerando")?.text()?.trim().orEmpty()
            val m = Regex("""S(\d+)\s*-\s*E(\d+)""").find(numerando)
            val temporada = m?.groupValues?.get(1)?.toIntOrNull()
            val capitulo = m?.groupValues?.get(2)?.toIntOrNull()
            val miniatura = li.selectFirst(".imagen img")?.attr("data-src")?.takeIf { it.startsWith("http") }
            episodios.add(
                newEpisode(enlace.attr("href")) {
                    this.name = enlace.text().trim().ifEmpty { numerando }
                    this.season = temporada
                    this.episode = capitulo
                    this.posterUrl = miniatura
                }
            )
        }

        if (episodios.isNotEmpty()) {
            return newTvSeriesLoadResponse(titulo, url, TvType.TvSeries, episodios) {
                this.posterUrl = poster
                this.plot = sinopsis
                this.tags = generos
                if (puntos != null) this.score = com.lagradost.cloudstream3.Score.from10(puntos)
            }
        }

        return newMovieLoadResponse(titulo, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = sinopsis
            this.tags = generos
            if (puntos != null) this.score = com.lagradost.cloudstream3.Score.from10(puntos)
        }
    }

    // ---------------------------------------------------------------- enlaces

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val emitidos = mutableSetOf<String>()

        val opciones = doc.select("#playeroptionsul li.dooplay_player_option[data-option]")
            .filterNot { it.hasClass("dooplay_player_option_trailer") }
            .mapNotNull { it.attr("data-option").takeIf { u -> u.startsWith("http") } }
            .distinct()
        val embeds = if (opciones.isNotEmpty()) opciones
        else doc.select("iframe[src]").map { it.attr("src") }.filter { it.startsWith("http") }

        embeds.forEach { embed ->
            if (!emitidos.add(embed)) return@forEach
            if (embed.contains("vimeos.net") || embed.contains("vimeos.")) {
                resolveVimeos(embed, callback, subtitleCallback)
            } else if (embed.contains("youtube.com") || embed.contains("youtu.be")) {
                return@forEach
            } else {
                loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return emitidos.isNotEmpty()
    }

    /** Vimeos: el embed esconde un packer con la lista de sources del jwplayer. */
    private suspend fun resolveVimeos(
        embed: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val respuesta = runCatching { app.get(embed, referer = "$mainUrl/") }.getOrNull() ?: return false
        val descomprimido = runCatching { getAndUnpack(respuesta.text) }.getOrNull()
        val codigo = if (descomprimido.isNullOrBlank()) respuesta.text else descomprimido

        val enlaces = m3u8Regex.findAll(codigo).map { it.groupValues[1] }.distinct().toList()
        if (enlaces.isEmpty()) {
            loadExtractor(embed, "$mainUrl/", subtitleCallback, callback)
            return false
        }

        enlaces.forEach { hls ->
            // Se entrega el master completo: lleva las pistas de audio
            // (#EXT-X-MEDIA ... NAME="Latine" DEFAULT=YES) y el ABR elige la calidad.
            // Si se parte por variante se pierde el grupo de audio y suena el original.
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name Latino",
                    url = hls,
                    type = ExtractorLinkType.M3U8
                ) {
                    // El CDN de Vimeos no pide Referer (comprobado en master,
                    // playlist y segmentos). Dejarlo vacio evita que CloudStream
                    // tenga que pasar el video por su proxy local, que es lo que
                    // producia los cortes al reproducir.
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }

    // ---------------------------------------------------------------- helpers

    private fun Document.cards(): List<SearchResponse> {
        val vistos = mutableSetOf<String>()
        val salida = mutableListOf<SearchResponse>()
        this.select("article.item").forEach { articulo ->
            val href = articulo.select("a[href]")
                .map { it.attr("href") }
                .firstOrNull { fichaRegex.containsMatchIn(it) } ?: return@forEach
            if (!vistos.add(href)) return@forEach

            val alt = articulo.selectFirst("img[alt]")?.attr("alt")?.trim()
            val titulo = alt?.takeIf { it.isNotEmpty() && it != "promo" && it != "poster" }
                ?: articulo.selectFirst(".in_title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: articulo.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            val poster = (articulo.selectFirst("img[data-src]")?.attr("data-src")
                ?: articulo.selectFirst("img[src^='https://image']")?.attr("src"))
                ?.takeIf { it.startsWith("http") }

            salida.add(
                if (href.contains("/ver-serie/")) {
                    newTvSeriesSearchResponse(titulo, href, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(titulo, href, TvType.Movie) { this.posterUrl = poster }
                }
            )
        }
        return salida
    }
}
