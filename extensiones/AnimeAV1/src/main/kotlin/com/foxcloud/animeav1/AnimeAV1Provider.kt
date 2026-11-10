package com.foxcloud.animeav1

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * AnimeAV1 (animeav1.com) - catalogo de anime en espanol.
 *
 * La web es SvelteKit con render en servidor: todo el contenido util esta en
 * el HTML. Los servidores de cada episodio viven ademas en su `__data.json`
 * (formato devalue de SvelteKit: un array donde los enteros son indices).
 *
 * Nota sobre imagenes: los `backdrops/<id>.jpg` del CDN devuelven 403 en
 * muchisimas fichas (la web los pinta igual, pero el fichero no existe), asi
 * que aqui solo se usan `covers/<id>.jpg` y `thumbnails/<id>.jpg`, que si
 * responden siempre. CloudStream no dibuja capas sobre la portada, por eso el
 * numero de capitulo de "Capitulos recientes" llega ya compuesto en la imagen
 * (carpeta caps/ del repositorio).
 */
class AnimeAV1Provider : MainAPI() {
    override var mainUrl = "https://animeav1.com"
    override var name = "AnimeAV1"
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false

    // Todas las secciones son listas reales y distintas de la propia web.
    override val mainPage = mainPageOf(
        "recientes" to "Capitulos recientes",
        "agregados" to "Recien agregados",
        "order=popular" to "Populares",
        "order=score" to "Mejor puntuados",
        "status=emision" to "En emision",
        "category=pelicula" to "Peliculas"
    )

    private val mapper = ObjectMapper()
    private val yearRegex = Regex("(19|20)\\d{2}")
    private val numeroRegex = Regex("(\\d+)")
    private val idImagenRegex = Regex("/(\\d+)\\.jpg")
    private val episodeHrefRegex = Regex("/media/([^/]+)/(\\d+)$")
    private val seriesHrefRegex = Regex("^(https?://[^/]+)?(/media/[^/?#]+)")

    // ---------------------------------------------------------------- portada

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return when (request.data) {
            // Estas dos filas son bloques de la portada y el sitio publica
            // exactamente 20 tarjetas en cada uno, sin listado paginado. Aqui
            // no se rellena con nada: o es un episodio recien actualizado, o no
            // entra en la fila.
            "recientes" -> newHomePageResponse(request.name, bloqueRecientes(), false)
            "agregados" -> newHomePageResponse(request.name, bloqueAgregados(), false)

            // Estas si son listas del catalogo con su mismo filtro y su mismo
            // orden, asi que la pagina 2, 3... sigue siendo la misma lista.
            else -> {
                val desde = (page - 1) * BLOQUES + 1
                val (items, hayMas) = catalogoBloque(request.data, desde)
                newHomePageResponse(request.name, items, hayMas)
            }
        }
    }

    /**
     * "Episodios / Recientemente actualizado". Cada tarjeta es un episodio: se
     * agrupa por serie (la primera aparicion es el capitulo mas nuevo) y la
     * portada es la compuesta con el numero del capitulo; si esa imagen aun no
     * existe se usa la portada limpia de la obra.
     */
    private suspend fun bloqueRecientes(): List<SearchResponse> {
        val doc = app.get(mainUrl).document
        val seccion = seccionPorTitulo(doc, "recientemente actualizado")
            ?: doc.select("section").firstOrNull { it.selectFirst("a[href^=/media/]") != null }
            ?: return emptyList()
        val caps = capsDisponibles()

        val vistos = mutableSetOf<String>()
        val salida = mutableListOf<SearchResponse>()
        seccion.select("article").forEach { articulo ->
            val enlace = articulo.selectFirst("a[href^=/media/]") ?: return@forEach
            val limpio = urlDeSerie(fixUrl(enlace.attr("href"))) ?: return@forEach
            if (!vistos.add(limpio)) return@forEach

            val titulo = articulo.selectFirst("h3, header div")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@forEach
            val imagen = articulo.selectFirst("img")?.attr("src")
            val id = idDeImagen(imagen)
            val capitulo = numeroDeCapitulo(articulo)

            val poster = when {
                id != null && capitulo != null && caps.contains("$id-$capitulo") ->
                    "$CAPS_BASE/$id-$capitulo.jpg?v=$CAPS_VER"

                id != null -> "$CDN/covers/$id.jpg"
                else -> posterDeTarjeta(articulo)
            }

            salida.add(
                newAnimeSearchResponse(titulo, limpio, TvType.Anime) {
                    this.posterUrl = poster
                }
            )
        }
        return salida
    }

    /** "Animes / Recientemente agregados": portadas normales del catalogo. */
    private suspend fun bloqueAgregados(): List<SearchResponse> {
        val doc = app.get(mainUrl).document
        val seccion = seccionPorTitulo(doc, "recientemente agregados") ?: return emptyList()

        val vistos = mutableSetOf<String>()
        val salida = mutableListOf<SearchResponse>()
        seccion.select("article").forEach { articulo ->
            val enlace = articulo.selectFirst("a[href^=/media/]") ?: return@forEach
            val limpio = urlDeSerie(fixUrl(enlace.attr("href"))) ?: return@forEach
            if (!vistos.add(limpio)) return@forEach

            val titulo = articulo.selectFirst("h3, header div")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@forEach
            val poster = posterDeTarjeta(articulo)

            salida.add(
                newAnimeSearchResponse(titulo, limpio, tipoDesde(articulo.selectFirst("div.bg-line")?.text())) {
                    this.posterUrl = poster
                }
            )
        }
        return salida
    }

    /**
     * Paginas del catalogo en paralelo. Devuelve BLOQUES paginas ya unidas y si
     * queda alguna mas (se pide una extra solo para comprobarlo).
     */
    private suspend fun catalogoBloque(data: String, desde: Int): Pair<List<SearchResponse>, Boolean> {
        val hasta = desde + BLOQUES
        val paginas: List<List<SearchResponse>> = (desde..hasta).toList().amap { p ->
            try {
                app.get(urlCatalogo(data, p)).document.cards()
            } catch (_: Exception) {
                emptyList()
            }
        }
        val items = paginas.dropLast(1).flatten().distinctBy { it.url }
        val hayMas = paginas.lastOrNull()?.isNotEmpty() == true
        return items to hayMas
    }

    private fun urlCatalogo(data: String, page: Int): String =
        if (data.isBlank()) "$mainUrl/catalogo?page=$page" else "$mainUrl/catalogo?$data&page=$page"

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
        val generos = doc.select("a[href^=/catalogo?genre=]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Solo covers: los backdrops dan 403 en muchas fichas y dejarian el
        // hueco de la portada vacio en el carrusel y en el detalle.
        val poster = doc.selectFirst("img[src*=/covers/]")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: doc.select("img").map { it.attr("src") }.firstOrNull { idDeImagen(it) != null }

        val sinopsis = doc.select(".entry p").joinToString("\n\n") { it.text().trim() }.ifBlank { null }

        val episodios = doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            val m = episodeHrefRegex.find(href) ?: return@mapNotNull null
            if (!href.contains(url.substringAfterLast('/'))) return@mapNotNull null
            m.groupValues[2].toIntOrNull()?.let { it to fixUrl(href) }
        }.distinctBy { it.first }.sortedBy { it.first }

        // Miniaturas por episodio: el CDN sirve screenshots/<id>/<cap>.jpg.
        // Si la serie no las tiene se usa su frame (thumbnails/<id>.jpg) y, en
        // el peor caso, la portada, para que ninguna tarjeta quede vacia.
        val serieId = idDeImagen(poster)
        val marcoSerie = serieId?.let { "$CDN/thumbnails/$it.jpg" }
        val primerCapitulo = episodios.firstOrNull()?.first
        val hayCapturas = serieId != null && primerCapitulo != null &&
            existeImagen("$CDN/screenshots/$serieId/$primerCapitulo.jpg")
        val hayMarco = !hayCapturas && marcoSerie != null && existeImagen(marcoSerie)

        fun posterDeCapitulo(numero: Int): String? = when {
            serieId != null && hayCapturas -> "$CDN/screenshots/$serieId/$numero.jpg"
            hayMarco -> marcoSerie
            else -> poster
        }

        // Ficha sin episodios (proximamente): nunca inventar una URL de play.
        if (episodios.isEmpty()) {
            return newAnimeLoadResponse(titulo, url, tipo) {
                this.posterUrl = poster
                this.year = anio
                this.plot = sinopsis
                this.tags = generos
                this.comingSoon = true
                this.addEpisodes(DubStatus.Subbed, emptyList())
            }
        }

        if (tipo == TvType.AnimeMovie) {
            return newMovieLoadResponse(titulo, url, TvType.AnimeMovie, episodios.first().second) {
                this.posterUrl = poster
                this.year = anio
                this.plot = sinopsis
                this.tags = generos
            }
        }

        return newAnimeLoadResponse(titulo, url, tipo) {
            this.posterUrl = poster
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
                        this.posterUrl = posterDeCapitulo(numero)
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
                            name = "SUB \u00b7 $name HLS",
                            url = hls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }

        // 2) Servidores alternativos del __data.json del episodio.
        // Cada uno va etiquetado con su idioma y ordenados SUB primero, asi lo
        // primero que suena es el subtitulado al espanol y el doblaje queda a mano.
        servidores(data).forEach { (idioma, servidor, enlace) ->
            if (servidor.equals("HLS", ignoreCase = true)) return@forEach
            if (!emitidos.add(enlace)) return@forEach
            val prefijo = if (idioma.isBlank()) "" else "$idioma \u00b7 "
            loadExtractor(enlace, "$mainUrl/", subtitleCallback) { video ->
                // Se reemite el enlace con el idioma delante. Se construye directo
                // porque newExtractorLink es suspend y aqui no hay corrutina.
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
        }

        return emitidos.isNotEmpty()
    }

    /**
     * Lee `__data.json` del episodio. SvelteKit serializa con devalue: un array
     * de valores donde cada entero dentro de un objeto es un indice a ese array.
     */
    private suspend fun servidores(episodioUrl: String): List<Triple<String, String, String>> {
        val texto = runCatching { app.get("$episodioUrl/__data.json").text }.getOrNull() ?: return emptyList()
        val raiz = runCatching { mapper.readTree(texto) }.getOrNull() ?: return emptyList()

        var valores: JsonNode? = null
        raiz.path("nodes").forEach { nodo ->
            val datos = nodo.path("data")
            if (datos.isArray) valores = datos
        }
        val arr = valores ?: return emptyList()

        fun texto(nodo: JsonNode?): String? = when {
            nodo == null -> null
            nodo.isTextual -> nodo.asText()
            nodo.isInt -> arr.get(nodo.asInt())?.asText()
            else -> null
        }

        fun resolverNodo(nodo: JsonNode?): JsonNode? = when {
            nodo == null -> null
            nodo.isInt -> arr.get(nodo.asInt())
            else -> nodo
        }

        // El JSON trae dos grupos con forma {"SUB": [servidores]}: primero los
        // reproductores y despues los de descarga. Se distinguen por el host.
        fun grupoDeServidores(objeto: JsonNode?): List<Triple<String, String, String>> {
            if (objeto == null || !objeto.isObject) return emptyList()
            val claves = objeto.fieldNames().asSequence().toList()
            if (claves.isEmpty() || claves.size > 8) return emptyList()
            if (claves.any { it.uppercase() !in IDIOMAS }) return emptyList()

            val salida = mutableListOf<Triple<String, String, String>>()
            for (idioma in claves) {
                val lista = resolverNodo(objeto.get(idioma)) ?: continue
                if (!lista.isArray) continue
                for (elemento in lista) {
                    val obj = resolverNodo(elemento) ?: continue
                    if (!obj.isObject) continue
                    val servidor = texto(obj.get("server")) ?: continue
                    val enlace = texto(obj.get("url") ?: obj.get("link") ?: obj.get("embed")) ?: continue
                    if (enlace.startsWith("http")) {
                        salida.add(Triple(idioma.uppercase(), servidor, enlace))
                    }
                }
            }
            return salida
        }

        val grupos = arr.map { grupoDeServidores(it) }.filter { it.isNotEmpty() }
        val elegido = grupos.firstOrNull { grupo ->
            grupo.any { REPRODUCTORES.contains(it.second.trim().lowercase()) }
        } ?: grupos.firstOrNull()

        if (elegido != null && elegido.isNotEmpty()) {
            return elegido.distinctBy { it.third }.sortedBy { entrada ->
                val i = ORDEN_IDIOMAS.indexOf(entrada.first)
                if (i >= 0) i else ORDEN_IDIOMAS.size
            }
        }

        // Respaldo: cualquier objeto suelto {server, url} del episodio, sin idioma.
        val sueltos = mutableListOf<Triple<String, String, String>>()
        arr.forEach { nodo ->
            if (!nodo.isObject) return@forEach
            val servidor = texto(nodo.get("server")) ?: return@forEach
            val enlace = texto(nodo.get("url") ?: nodo.get("link") ?: nodo.get("embed")) ?: return@forEach
            if (enlace.startsWith("http")) sueltos.add(Triple("", servidor, enlace))
        }
        return sueltos.distinctBy { it.third }
    }

    /** Idiomas que etiqueta el sitio; el primero manda (SUB = subtitulado al espanol). */
    private val ORDEN_IDIOMAS = listOf("SUB", "LAT", "CAST", "ESP", "DUB")
    private val IDIOMAS = ORDEN_IDIOMAS.toSet() + setOf("JAP", "RAW", "VO")

    /** Hosts que son reproductor, para no confundir ese grupo con el de descargas. */
    private val REPRODUCTORES = setOf(
        "hls", "voe", "byse", "mp4upload", "upnshare", "streamtape", "doodstream",
        "filemoon", "mixdrop", "vidhide", "lulu", "netu", "fembed", "streamwish",
        "vimeos", "ok.ru", "yandex", "pixeldrain"
    )

    // ---------------------------------------------------------------- helpers

    private companion object {
        const val CDN = "https://cdn.animeav1.com"
        const val CAPS_BASE =
            "https://raw.githubusercontent.com/harley654321/cloudstream-extensiones/main/caps"
        const val CAPS_TTL = 60 * 60 * 1000L

        /**
         * Se sube cuando cambia el diseno de las portadas compuestas. CloudStream
         * (y cualquier cache de imagenes) guarda por URL, asi que sin esto se
         * sigue viendo la version vieja aunque el fichero ya sea otro.
         */
        const val CAPS_VER = "2"

        /** Paginas de la web que se juntan por cada pagina de CloudStream. */
        const val BLOQUES = 10

        @Volatile
        private var capsCache: Set<String>? = null

        @Volatile
        private var capsCacheAt = 0L
    }

    /** Indice de portadas ya compuestas con el numero de capitulo. */
    private suspend fun capsDisponibles(): Set<String> {
        val ahora = System.currentTimeMillis()
        capsCache?.let { if (ahora - capsCacheAt < CAPS_TTL) return it }
        val nuevo = runCatching {
            mapper.readTree(app.get("$CAPS_BASE/index.json?v=$CAPS_VER").text).fieldNames()
                .asSequence().filter { !it.startsWith("_") }.toSet()
        }.getOrNull()
        if (nuevo != null) {
            capsCache = nuevo
            capsCacheAt = ahora
            return nuevo
        }
        return capsCache ?: emptySet()
    }

    /** Bloque de la portada localizado por su subtitulo. */
    private fun seccionPorTitulo(doc: Document, aguja: String): Element? {
        val etiqueta = doc.select("p, span, h2, h3, div")
            .filter { it.text().contains(aguja, ignoreCase = true) }
            .minByOrNull { it.text().length }
        return etiqueta?.closest("section")
    }

    /** El CDN responde 403 cuando la imagen no existe. */
    private suspend fun existeImagen(url: String): Boolean =
        runCatching { app.get(url).isSuccessful }.getOrDefault(false)

    private fun idDeImagen(url: String?): String? =
        url?.let { idImagenRegex.find(it)?.groupValues?.get(1) }

    private fun numeroDeCapitulo(articulo: Element): String? {
        val texto = articulo.selectFirst("div.bg-line")?.text()?.trim() ?: return null
        if (!texto.contains("episodio", ignoreCase = true)) return null
        return numeroRegex.find(texto)?.groupValues?.get(1)
    }

    private fun posterDeTarjeta(articulo: Element): String? {
        val imagenes = articulo.select("img").map { it.attr("src") }.filter { it.isNotBlank() }
        imagenes.firstOrNull { it.contains("/covers/") }?.let { return it }
        imagenes.firstOrNull()?.let { return idDeImagen(it)?.let { id -> "$CDN/covers/$id.jpg" } ?: it }
        return null
    }

    /** Tarjetas del catalogo / resultados de busqueda. */
    private fun Document.cards(): List<SearchResponse> {
        val vistos = mutableSetOf<String>()
        val salida = mutableListOf<SearchResponse>()
        this.select("article").forEach { articulo ->
            val enlace = articulo.selectFirst("a[href^=/media/]") ?: return@forEach
            val limpio = urlDeSerie(fixUrl(enlace.attr("href"))) ?: return@forEach
            if (!vistos.add(limpio)) return@forEach

            val titulo = articulo.selectFirst("h3, header div")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@forEach
            val poster = posterDeTarjeta(articulo)
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
