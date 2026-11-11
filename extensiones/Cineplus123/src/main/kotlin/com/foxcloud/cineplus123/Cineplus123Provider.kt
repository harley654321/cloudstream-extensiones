package com.foxcloud.cineplus123

import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document

/**
 * Cineplus123 (cineplus123.org) - peliculas y series, WordPress con Dooplay.
 *
 * Rutas:
 *  - peliculas: /peliculas/<slug>/      archivo paginado /peliculas/
 *  - series:    /serie-de-tv/<slug>/    archivo paginado /serie-de-tv/
 *  - episodios: /capitulo/<slug>-<t>x<e>/
 *  - generos: /genero/<slug>/  (accion, drama, animacion...)
 *  - los /genero/latino|castellano|subtitulado son filtros de idioma, no secciones
 *  - paginacion: /page/N/ y texto "Pagina X de Y"
 *  - busqueda: /?s=<texto>
 *
 * Servidores: cada ficha lista li.dooplay_player_option[data-post|data-nume|data-type]
 * y el embed real se pide por AJAX a /wp-admin/admin-ajax.php (action=doo_player_ajax).
 * El titulo de cada opcion ya dice el idioma (LATINO HD, CASTELLANO, SUBTITULADO),
 * asi que se etiqueta y se ordena con el latino primero.
 */
class Cineplus123Provider : MainAPI() {
    override var mainUrl = "https://cineplus123.org"
    override var name = "Cineplus123"
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    // Solo secciones reales del sitio (autopsia del 2026-11-10):
    //  - /peliculas/ 224 paginas, /serie-de-tv/ 22 paginas
    //  - /ano/<anio>/ (archivo por anio, el actual tiene 11 paginas)
    //  - /genero/accion/ 55 paginas, /genero/animacion/ 20 paginas
    // Los /genero/latino|castellano|subtitulado NO van aqui: son filtros de
    // idioma y listan los mismos titulos. El idioma se elige en los servidores.
    override val mainPage = mainPageOf(
        "peliculas" to "Peliculas",
        "series" to "Series",
        "estrenos" to ("Estrenos " + java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)),
        "accion" to "Accion",
        "animacion" to "Animacion"
    )

    private val paginaRegex = Regex("""/page/(\d+)/?""")
    private val totalRegex = Regex("""de\s+(\d+)""")
    private val fichaRegex = Regex("""/(peliculas|serie-de-tv)/[a-z0-9-]+/?$""")
    private val episodioRegex = Regex("""-(\d+)x(\d+)/?$""")
    private val embedRegex = Regex("""embed_url\s*:\s*"(.*?)"""")
    private val fileRegex = Regex("""file\s*:\s*"(https?://[^"]+)"""")

    private val idiomas = listOf("LATINO", "CASTELLANO", "SUBTITULADO")
    private val etiquetasIdioma = setOf("latino", "castellano", "subtitulado")

    // ---------------------------------------------------------------- portada

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Igual que en las otras extensiones: cada peticion de fila une un lote de
        // paginas del sitio para que la fila llegue larga y el deslizamiento siga.
        val porLote = 10
        val desde = (page - 1) * porLote + 1
        val hasta = desde + porLote - 1

        val documentos = (desde..hasta).toList().amap { p ->
            runCatching { app.get(urlLista(request.data, p)).document }.getOrNull()
        }.filterNotNull()

        val items = documentos.flatMap { it.cards() }.distinctBy { it.url }
        val ultima = documentos.mapNotNull { maxPagina(it) }.maxOrNull() ?: hasta
        return newHomePageResponse(request.name, items, ultima > hasta)
    }

    private fun anioActual(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    private fun urlLista(kind: String, page: Int): String {
        val base = mainUrl.trimEnd('/') + when (kind) {
            "series" -> "/serie-de-tv/"
            "estrenos" -> "/ano/" + anioActual() + "/"
            "accion" -> "/genero/accion/"
            "animacion" -> "/genero/animacion/"
            else -> "/peliculas/"
        }
        // Concatenado a mano, sin plantillas: asi no hay forma de escaparlo mal.
        return if (page <= 1) base else base.trimEnd('/') + "/page/" + page + "/"
    }

    private fun maxPagina(doc: Document): Int? {
        // El sitio dice "Pagina 1 de 22" y solo enlaza 1, 2, 3 y "siguiente",
        // asi que primero se lee el total del texto y solo si falta se miran enlaces.
        val texto = doc.selectFirst(".pagination, .wp-pagenavi")?.text().orEmpty()
        totalRegex.find(texto)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return doc.select(".pagination a[href], .wp-pagenavi a[href]")
            .mapNotNull { a -> paginaRegex.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()
    }

    private fun Document.cards(): List<SearchResponse> {
        val salida = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()
        select("article.item").forEach { articulo ->
            val href = articulo.select("a[href]").map { it.attr("href") }
                .firstOrNull { fichaRegex.containsMatchIn(it) } ?: return@forEach
            if (!vistos.add(href)) return@forEach
            val alt = articulo.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
            val titulo = alt.takeIf { it.isNotEmpty() && it != "Pelis123" }
                ?: articulo.selectFirst(".title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            val poster = articulo.select("img[data-src], img[src]")
                .map { it.attr("data-src").ifBlank { it.attr("src") } }
                .firstOrNull { it.startsWith("http") && !it.contains("logo") && !it.contains("play.svg") }
            val esSerie = href.contains("/serie-de-tv/")
            salida.add(
                if (esSerie) {
                    newTvSeriesSearchResponse(titulo, href, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(titulo, href, TvType.Movie) { this.posterUrl = poster }
                }
            )
        }
        return salida
    }

    // ---------------------------------------------------------------- busqueda

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(mainUrl, params = mapOf("s" to query)).document
        return doc.cards()
    }

    // ------------------------------------------------------------------ ficha

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val titulo = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = doc.select("img[data-src]")
            .map { Pair(it.attr("alt").trim(), it.attr("data-src")) }
            .firstOrNull { it.second.startsWith("http") && !it.second.contains("logo") }
            ?.second

        val sinopsis = (doc.select(".wp-content p").map { it.text().trim() } +
            doc.select("[itemprop=description]").map { it.text().trim() })
            .firstOrNull { it.length > 60 }

        val etiquetas = doc.select("a[href*='/genero/']")
            .filter { !etiquetasIdioma.contains(it.attr("href").trimEnd('/').substringAfterLast('/')) }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val anio = doc.select("a[href*='/ano/']").mapNotNull { it.text().trim().toIntOrNull() }
            .firstOrNull()

        val episodios = mutableListOf<Episode>()
        doc.select("#seasons .se-c").forEachIndexed { indice, bloque ->
            val cabecera = bloque.selectFirst(".se-q, .se-t")?.text().orEmpty()
            val temporadaCabecera = Regex("""(\d+)""").find(cabecera)?.groupValues?.get(1)?.toIntOrNull()
            bloque.select("ul.episodes li a[href], ul.episodios li a[href]").forEach { enlace ->
                val href = enlace.attr("href")
                if (!href.contains("/capitulo/")) return@forEach
                val m = episodioRegex.find(href)
                val temporada = m?.groupValues?.get(1)?.toIntOrNull() ?: temporadaCabecera ?: (indice + 1)
                val capitulo = m?.groupValues?.get(2)?.toIntOrNull()
                val nombre = enlace.text().trim().ifEmpty { "Episodio " + (capitulo ?: 0) }
                episodios.add(
                    newEpisode(href) {
                        this.name = nombre
                        this.season = temporada
                        this.episode = capitulo
                    }
                )
            }
        }

        if (episodios.isNotEmpty()) {
            return newTvSeriesLoadResponse(titulo, url, TvType.TvSeries, episodios) {
                this.posterUrl = poster
                this.plot = sinopsis
                this.tags = etiquetas
                this.year = anio
            }
        }

        return newMovieLoadResponse(titulo, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = sinopsis
            this.tags = etiquetas
            this.year = anio
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

        data class Opcion(val post: String, val nume: String, val tipo: String, val etiqueta: String)

        val opciones = doc.select("li.dooplay_player_option")
            .mapNotNull { li ->
                val nume = li.attr("data-nume")
                val post = li.attr("data-post")
                if (nume.isBlank() || post.isBlank() || nume.equals("trailer", true)) return@mapNotNull null
                val texto = li.selectFirst(".title")?.text()?.trim()
                    ?: li.text().trim()
                Opcion(post, nume, li.attr("data-type").ifBlank { "movie" }, texto.replace("HD", "").trim())
            }
            .sortedBy { opcion ->
                val i = idiomas.indexOfFirst { opcion.etiqueta.uppercase().startsWith(it) }
                if (i >= 0) i else idiomas.size
            }

        val emitidos = mutableSetOf<String>()
        var alguno = false

        for (opcion in opciones) {
            val embed = embedDe(opcion.post, opcion.nume, opcion.tipo, data) ?: continue
            if (!emitidos.add(embed)) continue
            if (embed.contains("youtube.com") || embed.contains("youtu.be")) continue

            val prefijo = if (opcion.etiqueta.isBlank()) "" else opcion.etiqueta + " \u00b7 "

            if (embed.contains("byse")) {
                if (byse(embed, opcion.etiqueta, callback)) {
                    alguno = true
                    continue
                }
            }

            loadExtractor(embed, data, subtitleCallback) { video ->
                // Se reemite con el idioma delante para que se vea en la lista.
                @Suppress("DEPRECATION")
                callback(
                    ExtractorLink(
                        source = video.source,
                        name = prefijo + video.name,
                        url = video.url,
                        referer = video.referer,
                        quality = video.quality,
                        headers = video.headers,
                        extractorData = video.extractorData,
                        type = video.type,
                        audioTracks = video.audioTracks
                    )
                )
                alguno = true
            }
        }

        return alguno
    }

    /** Dooplay resuelve el embed real por AJAX; devuelve la URL del reproductor. */
    private suspend fun embedDe(post: String, nume: String, tipo: String, referer: String): String? {
        val texto = runCatching {
            app.post(
                mainUrl.trimEnd('/') + "/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to tipo
                ),
                referer = referer,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
        }.getOrNull() ?: return null
        val embed = embedRegex.find(texto)?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
        return embed.takeIf { it.startsWith("http") }
    }

    /** Byse: su reproductor es una SPA que pide los sources a /api/videos/<codigo>/embed/details. */
    private suspend fun byse(embed: String, etiqueta: String, callback: (ExtractorLink) -> Unit): Boolean {
        val codigo = embed.trimEnd('/').substringAfterLast('/')
        val host = embed.substringAfter("://").substringBefore('/')
        if (codigo.isBlank() || host.isBlank()) return false

        val json = runCatching {
            app.get("https://" + host + "/api/videos/" + codigo + "/embed/details", referer = embed).text
        }.getOrNull() ?: return false

        val archivos = fileRegex.findAll(json).map { it.groupValues[1] }.distinct().toList()
        if (archivos.isEmpty()) return false

        val prefijo = if (etiqueta.isBlank()) "" else etiqueta + " \u00b7 "
        archivos.forEach { archivo ->
            callback(
                newExtractorLink(
                    source = this.name,
                    name = prefijo + host.substringBefore('.'),
                    url = archivo,
                    type = if (archivo.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}
