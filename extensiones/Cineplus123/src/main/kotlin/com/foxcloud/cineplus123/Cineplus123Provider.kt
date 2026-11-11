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
import com.lagradost.cloudstream3.utils.getAndUnpack
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
        "destacados" to "Titulos destacados",
        "peliculas" to "Peliculas",
        "series" to "Series de TV",
        "top_movies" to "TOP Movies",
        "top_series" to "TOP Series"
    )

    private val paginaRegex = Regex("""/page/(\d+)/?""")
    private val totalRegex = Regex("""de\s+(\d+)""")
    private val fichaRegex = Regex("""/(peliculas|serie-de-tv)/[a-z0-9-]+/?$""")
    private val episodioRegex = Regex("""-(\d+)x(\d+)/?$""")
    /** Lee un campo de un JSON pequeño sin depender de comillas ni de regex. */
    private fun campoJson(texto: String, clave: String): String? {
        val limpio = texto.replace(" ", "").replace("\n", "").replace("\r", "")
        val marca = "\"" + clave + "\":\""
        val inicio = limpio.indexOf(marca)
        if (inicio < 0) return null
        val valor = limpio.substring(inicio + marca.length).substringBefore('"')
        return valor.ifBlank { null }?.replace("\\/", "/")
    }
    private val fileRegex = Regex("""file\s*:\s*"(https?://[^"]+)"""")

    private val idiomas = listOf("LATINO", "CASTELLANO", "SUBTITULADO")
    private val etiquetasIdioma = setOf("latino", "castellano", "subtitulado")

    // ---------------------------------------------------------------- portada

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Destacados y los dos TOP son listas fijas del sitio: una sola pagina.
        if (request.data == "destacados") {
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            val doc = runCatching { app.get(mainUrl).document }.getOrNull()
            val items = doc?.let { destacados(it) } ?: emptyList()
            return newHomePageResponse(request.name, items)
        }
        if (request.data == "top_movies" || request.data == "top_series") {
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            val doc = runCatching { app.get(mainUrl.trimEnd('/') + "/imdb/").document }.getOrNull()
            val series = request.data == "top_series"
            val items = doc?.let { ranking(it, series) } ?: emptyList()
            return newHomePageResponse(request.name, items)
        }

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
        // El archivo trae arriba el carrusel (.animation-1.slider) y el bloque
        // .items.featured con los mismos titulos: se excluyen para que cada fila
        // muestre solo lo suyo y no se repita contenido entre paginas.
        select("article.item").filterNot { enCarrusel(it) }.forEach { articulo ->
            val href = articulo.select("a[href]").map { it.attr("href") }
                .firstOrNull { fichaRegex.containsMatchIn(it) } ?: return@forEach
            if (!vistos.add(href)) return@forEach
            val titulo = tituloDe(articulo) ?: return@forEach
            val poster = posterDe(articulo)
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

    /** El carrusel y el bloque destacado repiten titulos en todas las paginas. */
    private fun enCarrusel(articulo: org.jsoup.nodes.Element): Boolean {
        var padre: org.jsoup.nodes.Element? = articulo.parent()
        while (padre != null) {
            val clases = padre.classNames()
            if (clases.contains("slider") || clases.contains("featured") || clases.contains("animation-1")) return true
            padre = padre.parent()
        }
        return false
    }

    /** Portada de una tarjeta: lazy-load, src, o cualquier URL de imagen del bloque. */
    private fun posterDe(articulo: org.jsoup.nodes.Element): String? {
        val candidatos = mutableListOf<String>()
        articulo.select("img").forEach { img ->
            listOf("data-src", "data-lazy-src", "data-original", "src").forEach { attr ->
                candidatos.add(img.attr(attr))
            }
        }
        articulo.select("[style*=background]").forEach { el ->
            Regex("""url\(['"]?(https?://[^'")]+)""").find(el.attr("style"))?.groupValues?.get(1)?.let { candidatos.add(it) }
        }
        return candidatos.asSequence()
            .map { it.trim() }
            .map { if (it.startsWith("//")) "https:" + it else it }
            .firstOrNull { it.startsWith("http") && !esAdorno(it) }
            ?.let { grande(it) }
    }

    /** El logo del sitio, el icono de play y los marcadores de "sin imagen" no son portadas. */
    private fun esAdorno(url: String): Boolean =
        url.contains("logo") || url.contains("play.svg") || url.contains("no-poster") ||
            url.contains("no_poster") || url.contains("placeholder") || url.contains("blank.gif") ||
            url.endsWith(".svg") || url.startsWith("data:")

    /**
     * Portada de una ficha. En esta plantilla las tres primeras imagenes del HTML
     * son el logo (x2) y el icono de play, y la portada real esta en .poster.
     */
    private fun posterFicha(doc: Document): String? {
        val candidatos = mutableListOf<String>()
        doc.select(".poster img").forEach { img ->
            candidatos.add(img.attr("data-src"))
            candidatos.add(img.attr("src"))
        }
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { candidatos.add(it) }
        doc.select("#single img, .sheader img, img").forEach { img ->
            candidatos.add(img.attr("data-src"))
            candidatos.add(img.attr("src"))
        }
        return candidatos.asSequence()
            .map { it.trim() }
            .map { if (it.startsWith("//")) "https:" + it else it }
            .firstOrNull { it.startsWith("http") && !esAdorno(it) }
            ?.let { grande(it) }
    }

    /** Si una tarjeta no trae portada, se pide a su ficha (solo cuando falta). */
    private suspend fun posterFicha(href: String): String? =
        runCatching { posterFicha(app.get(href).document) }.getOrNull()

    /** Las tarjetas pequeñas traen la portada en 185px; se pide en 500px. */
    private fun grande(url: String): String =
        url.replace("/t/p/w92/", "/t/p/w500/").replace("/t/p/w185/", "/t/p/w500/").replace("/t/p/w300/", "/t/p/w500/")

    /** Carrusel de la portada (10-18 titulos reales del sitio). */
    private suspend fun destacados(doc: Document): List<SearchResponse> {
        val salida = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()
        doc.select(".animation-1.slider article.item, .items.featured article.item").forEach { articulo ->
            val href = articulo.select("a[href]").map { it.attr("href") }
                .firstOrNull { fichaRegex.containsMatchIn(it) } ?: return@forEach
            if (!vistos.add(href)) return@forEach
            val titulo = tituloDe(articulo) ?: return@forEach
            val poster = posterDe(articulo) ?: posterFicha(href)
            salida.add(
                if (href.contains("/serie-de-tv/")) {
                    newTvSeriesSearchResponse(titulo, href, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(titulo, href, TvType.Movie) { this.posterUrl = poster }
                }
            )
        }
        return salida
    }

    /** /imdb/ mezcla peliculas y series en un solo bloque: se separan por la ruta. */
    private suspend fun ranking(doc: Document, series: Boolean): List<SearchResponse> {
        val salida = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()
        doc.select(".top-imdb-item").forEach { bloque ->
            val href = bloque.select("a[href]").map { it.attr("href") }
                .firstOrNull { fichaRegex.containsMatchIn(it) } ?: return@forEach
            if (href.contains("/serie-de-tv/") != series) return@forEach
            if (!vistos.add(href)) return@forEach
            val titulo = tituloDe(bloque) ?: return@forEach
            val poster = posterDe(bloque) ?: posterFicha(href)
            salida.add(
                if (series) newTvSeriesSearchResponse(titulo, href, TvType.TvSeries) { this.posterUrl = poster }
                else newMovieSearchResponse(titulo, href, TvType.Movie) { this.posterUrl = poster }
            )
        }
        return salida
    }

    private fun tituloDe(articulo: org.jsoup.nodes.Element): String? =
        articulo.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() && it != "Pelis123" }
            ?: articulo.selectFirst(".title")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- busqueda

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(mainUrl, params = mapOf("s" to query)).document
        // El buscador del tema no usa article.item: devuelve div.result-item,
        // cada uno con su ficha en .details .title a y la portada en img[data-src].
        val resultados = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()
        doc.select("div.result-item").forEach { fila ->
            val href = fila.select("a[href]").map { it.attr("href") }
                .firstOrNull { fichaRegex.containsMatchIn(it) } ?: return@forEach
            if (!vistos.add(href)) return@forEach
            val titulo = fila.selectFirst(".details .title a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: tituloDe(fila) ?: return@forEach
            val poster = posterDe(fila) ?: posterFicha(href)
            val esSerie = href.contains("/serie-de-tv/")
            resultados.add(
                if (esSerie) {
                    newTvSeriesSearchResponse(titulo, href, TvType.TvSeries) { this.posterUrl = poster }
                } else {
                    newMovieSearchResponse(titulo, href, TvType.Movie) { this.posterUrl = poster }
                }
            )
        }
        return if (resultados.isNotEmpty()) resultados else doc.cards()
    }

    // ------------------------------------------------------------------ ficha

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val titulo = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = posterFicha(doc)

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
            val embed = runCatching { embedDe(opcion.post, opcion.nume, opcion.tipo, data) }.getOrNull() ?: continue
            if (!emitidos.add(embed)) continue
            if (embed.contains("youtube.com") || embed.contains("youtu.be")) continue

            // Los reproductores los resuelve CloudStream con su lista global:
            // si esta instalada la extension de resolvedores, los encuentra.
            runCatching {
                loadExtractor(embed, data, subtitleCallback) { video ->
                    etiquetado(video, opcion.etiqueta) { link ->
                        alguno = true
                        callback(link)
                    }
                }
            }
        }

        return alguno
    }

    /** Reemite un enlace del extractor con el idioma delante. */
    private fun etiquetado(video: ExtractorLink, etiqueta: String, callback: (ExtractorLink) -> Unit) {
        val prefijo = if (etiqueta.isBlank()) "" else etiqueta + " \u00b7 "
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
    }

    /** Dooplay resuelve el embed real por AJAX; devuelve la URL del reproductor. */
    private suspend fun embedDe(post: String, nume: String, tipo: String, referer: String): String? {
        val base = mainUrl.trimEnd('/') + "/wp-admin/admin-ajax.php"
        val cuerpo = mapOf(
            "action" to "doo_player_ajax",
            "post" to post,
            "nume" to nume,
            "type" to tipo
        )
        // 1) POST normal
        runCatching {
            app.post(base, data = cuerpo, referer = referer).text
        }.getOrNull()?.let { texto -> campoJson(texto, "embed_url") }?.let { return it }

        // 2) el sitio a veces espera otro "type" (movie/tv)
        val otro = if (tipo == "tv") "movie" else "tv"
        runCatching {
            app.post(base, data = cuerpo + ("type" to otro), referer = referer).text
        }.getOrNull()?.let { texto -> campoJson(texto, "embed_url") }?.let { return it }

        // 3) ultimo recurso: por GET
        val consulta = "?action=doo_player_ajax&post=" + post + "&nume=" + nume + "&type=" + tipo
        runCatching { app.get(base + consulta, referer = referer).text }
            .getOrNull()?.let { texto -> campoJson(texto, "embed_url") }?.let { return it }

        return null
    }
}
