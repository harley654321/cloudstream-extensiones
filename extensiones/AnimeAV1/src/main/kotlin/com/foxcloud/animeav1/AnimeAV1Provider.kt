package com.foxcloud.animeav1

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * AnimeAV1 (animeav1.com) - catalogo de anime en espanol.
 *
 * La web es SvelteKit con render en servidor, asi que todo el contenido util
 * esta en el HTML y se parsea con Jsoup. Los servidores del reproductor viven
 * en el endpoint `__data.json` de cada episodio (formato devalue de SvelteKit:
 * un array donde los enteros son referencias a otros indices).
 */
class AnimeAV1Provider : MainAPI() {
    override var mainUrl = "https://animeav1.com"
    override var name = "AnimeAV1"
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false

    // Secciones de portada: todas sirven listas distintas de la propia web.
    override val mainPage = mainPageOf(
        "recientes" to "Recientes",
        "agregados" to "Recien agregados",
        "status=emision" to "En emision",
        "category=pelicula" to "Peliculas",
        "genre=isekai" to "Isekai"
    )

    private val mapper = ObjectMapper()
    private val yearRegex = Regex("(19|20)\\d{2}")
    private val episodeHrefRegex = Regex("/media/([^/]+)/(\\d+)$")
    private val seriesHrefRegex = Regex("^(https?://[^/]+)?(/media/[^/?#]+)")

    // ---------------------------------------------------------------- portada

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        return when (data) {
            "recientes" -> newHomePageResponse(
                request.name,
                homeSection("recientemente actualizado", true),
                false
            )

            "agregados" -> newHomePageResponse(
                request.name,
                homeSection("recientemente agregados", false),
                false
            )

            else -> {
                val doc = app.get("$mainUrl/catalogo?$data&page=$page").document
                val items = doc.cards()
                val hasNext = doc.selectFirst("a[href*=page=${page + 1}]") != null
                newHomePageResponse(request.name, items, hasNext)
            }
        }
    }

    /** Extrae un bloque de la portada localizandolo por el subtitulo de la seccion. */
    private suspend fun homeSection(subtitulo: String, esEpisodio: Boolean): List<SearchResponse> {
        val doc = app.get(mainUrl).document
        val etiqueta = doc.select("p").firstOrNull { it.text().contains(subtitulo, ignoreCase = true) }
        val seccion = etiqueta?.closest("section") ?: return emptyList()

        val vistos = mutableSetOf<String>()
        val salida = mutableListOf<SearchResponse>()
        seccion.select("article").forEach { articulo ->
            val enlace = articulo.selectFirst("a[href^=/media/]") ?: return@forEach
            val limpio = urlDeSerie(fixUrl(enlace.attr("href"))) ?: return@forEach
            if (!vistos.add(limpio)) return@forEach

            val titulo = articulo.selectFirst("header div, header h3, h3")?.text()?.trim()
                ?: return@forEach
            val poster = articulo.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            val tipo = if (esEpisodio) TvType.Anime else tipoDesde(articulo.selectFirst("div.bg-line")?.text())

            salida.add(
                newAnimeSearchResponse(titulo, limpio, tipo) {
                    this.posterUrl = poster
                }
            )
        }
        return salida
    }

    // ---------------------------------------------------------------- busqueda

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get("$mainUrl/catalogo", params = mapOf("search" to query)).document
        return doc.cards()
    }

    // ------------------------------------------------------------------ ficha

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val titulo = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val spans = doc.select("header div.flex.flex-wrap.items-center > span").map { it.text().trim() }
        val tipo = tipoDesde(spans.firstOrNull())
        val anio = spans.firstOrNull { yearRegex.matches(it) }?.toIntOrNull()
        val generos = doc.select("a[href^=/catalogo?genre=]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val poster = doc.selectFirst("img[src*=/covers/]")?.attr("src")
        val fondo = doc.selectFirst("img[src*=/backdrops/]")?.attr("src")
        val sinopsis = doc.select(".entry p").joinToString("\n\n") { it.text().trim() }.ifBlank { null }
        val puntos = doc.selectFirst("div.ic-star-solid div.text-2xl")?.text()?.trim()?.replace(",", ".")?.toDoubleOrNull()

        val episodios = doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            val m = episodeHrefRegex.find(href) ?: return@mapNotNull null
            if (!href.contains(url.substringAfterLast('/'))) return@mapNotNull null
            m.groupValues[2].toIntOrNull()?.let { it to fixUrl(href) }
        }.distinctBy { it.first }.sortedBy { it.first }

        if (tipo == TvType.AnimeMovie || episodios.isEmpty()) {
            val directo = episodios.firstOrNull()?.second ?: "$url/1"
            return newMovieLoadResponse(titulo, url, TvType.AnimeMovie, directo) {
                this.posterUrl = poster
                this.backgroundPosterUrl = fondo
                this.year = anio
                this.plot = sinopsis
                this.tags = generos
            }
        }

        return newAnimeLoadResponse(titulo, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = fondo
            this.year = anio
            this.plot = sinopsis
            this.tags = generos
            this.comingSoon = false
            this.addEpisodes(
                DubStatus.Subbed,
                episodios.map { (numero, enlace) ->
                    newEpisode(enlace) {
                        this.episode = numero
                        this.name = "Episodio $numero"
                    }
                }
            )
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

        // 1) Reproductor propio: /play/<hash> -> /m3u8/<hash>
        doc.selectFirst("iframe[src*=/play/]")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { play ->
                val hls = play.replace("/play/", "/m3u8/")
                if (emitidos.add(hls)) {
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "$name HLS",
                            url = hls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }

        // 2) Servidores alternativos del __data.json del episodio
        servidores(data).forEach { (servidor, enlace) ->
            if (servidor.equals("HLS", ignoreCase = true)) return@forEach
            if (!emitidos.add(enlace)) return@forEach
            loadExtractor(enlace, "$mainUrl/", subtitleCallback, callback)
        }

        return emitidos.isNotEmpty()
    }

    /**
     * Lee `__data.json` del episodio. SvelteKit serializa con devalue: un array
     * de valores donde cada entero dentro de un objeto es un indice a ese array.
     */
    private suspend fun servidores(episodioUrl: String): List<Pair<String, String>> {
        val texto = runCatching { app.get("$episodioUrl/__data.json").text }.getOrNull() ?: return emptyList()
        val raiz = runCatching { mapper.readTree(texto) }.getOrNull() ?: return emptyList()

        var valores: JsonNode? = null
        raiz.path("nodes").forEach { nodo ->
            val datos = nodo.path("data")
            if (datos.isArray) valores = datos
        }
        val arr = valores ?: return emptyList()

        fun resolver(nodo: JsonNode?): String? = when {
            nodo == null -> null
            nodo.isTextual -> nodo.asText()
            nodo.isInt -> arr.get(nodo.asInt())?.asText()
            else -> null
        }

        val salida = mutableListOf<Pair<String, String>>()
        arr.forEach { nodo ->
            if (nodo.isObject && nodo.has("server") && nodo.has("url")) {
                val servidor = resolver(nodo.get("server"))
                val enlace = resolver(nodo.get("url"))
                if (servidor != null && enlace != null && enlace.startsWith("http")) {
                    salida.add(servidor to enlace)
                }
            }
        }
        return salida.distinct()
    }

    // ---------------------------------------------------------------- helpers

    /** Tarjetas del catalogo / resultados de busqueda. */
    private fun org.jsoup.nodes.Document.cards(): List<SearchResponse> {
        val vistos = mutableSetOf<String>()
        val salida = mutableListOf<SearchResponse>()
        this.select("article").forEach { articulo ->
            val enlace = articulo.selectFirst("a[href^=/media/]") ?: return@forEach
            val limpio = urlDeSerie(fixUrl(enlace.attr("href"))) ?: return@forEach
            if (!vistos.add(limpio)) return@forEach

            val titulo = articulo.selectFirst("h3, header div")?.text()?.trim() ?: return@forEach
            val poster = articulo.selectFirst("img[src*=/covers/]")?.attr("src")
                ?: articulo.selectFirst("img")?.attr("src")
            val tipo = tipoDesde(articulo.selectFirst("div.bg-line")?.text())

            salida.add(
                newAnimeSearchResponse(titulo, limpio, tipo) {
                    this.posterUrl = poster
                }
            )
        }
        return salida
    }

    /** Normaliza cualquier enlace de la web a la URL de la serie. */
    private fun urlDeSerie(enlace: String): String? {
        val m = seriesHrefRegex.find(enlace) ?: return null
        return "$mainUrl${m.groupValues[2]}"
    }

    private fun tipoDesde(texto: String?): TvType {
        val t = texto?.lowercase() ?: return TvType.Anime
        return when {
            t.contains("pel") -> TvType.AnimeMovie
            t.contains("ova") -> TvType.OVA
            else -> TvType.Anime
        }
    }
}
