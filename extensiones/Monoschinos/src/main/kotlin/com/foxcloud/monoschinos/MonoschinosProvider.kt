package com.foxcloud.monoschinos

import android.util.Base64
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Monoschinos (monoschinos.st): anime, donghua y peliculas, con audio original
 * subtitulado y tambien titulos con doblaje latino y castellano.
 *
 * Detalles del sitio que hay que respetar:
 * - Todo va por ?p=N (portada, emision, catalogo y sus filtros).
 * - Los capitulos de una ficha se piden por AJAX (.caplist -> ajax_pagination ->
 *   caplist). Esos endpoints devuelven 403 si el User-Agent no es de navegador,
 *   asi que todas las peticiones llevan uno de Chrome.
 * - Los filtros son genero[] y categoria: pelicula, donghua, ova... y entre los
 *   generos estan "latino" y "castellano", que es como el sitio marca el doblaje.
 * - Los servidores de cada capitulo vienen en base64 dentro de button[data-player].
 */
class MonoschinosProvider : MainAPI() {
    override var mainUrl = "https://monoschinos.st"
    override var name = "Monoschinos"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cabeceras = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "*/*"
    )

    override val mainPage = mainPageOf(
        mainUrl + "/" to "Ultimos capitulos",
        mainUrl + "/emision?p=" to "En emision",
        mainUrl + "/animes?categoria=pelicula&p=" to "Peliculas",
        mainUrl + "/animes?categoria=donghua&p=" to "Donghua",
        mainUrl + "/animes?genero%5B%5D=accion&p=" to "Accion",
        mainUrl + "/animes?genero%5B%5D=latino&p=" to "Latino"
    )

    private val mapper = ObjectMapper()

    private companion object {
        /** Portadas ya compuestas con la etiqueta del capitulo. */
        const val CAPS_BASE =
            "https://raw.githubusercontent.com/harley654321/cloudstream-extensiones/main/caps/ms"

        /** Igual que DISENO en .github/scripts/caps_ms.py. */
        const val CAPS_VER = "1"
        const val CAPS_TTL = 60 * 60 * 1000L

        @Volatile
        private var capsCache: Set<String>? = null

        @Volatile
        private var capsCacheAt = 0L
    }

    /** Claves de portadas compuestas disponibles (se refresca cada hora). */
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

    /**
     * El sitio guarda dos imagenes por obra: "portada" (760x428, apaisada, la de
     * la fila de capitulos) e "imagen" (vertical, la del buscador y el
     * directorio). Se usa siempre la vertical para que la tarjeta se vea igual
     * en toda la app.
     */
    private fun retrato(url: String): String {
        val m = Regex("""/portada/(.+?)-portada-(\d+)\.jpg""").find(url) ?: return url
        val cola = if (url.contains("?")) "?v=6" else ""
        return mainUrl + "/assets/thumbs/imagen/" + m.groupValues[1] + "-" +
            m.groupValues[2] + ".jpg.webp" + cola
    }

    private val anioRegex = Regex("""(19|20)\d{2}""")
    private val episodioEnUrl = Regex("""-episodio-(\d+)""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val portada = request.data == mainUrl + "/"
        val url = if (portada) mainUrl else request.data + page
        val doc = app.get(url, headers = cabeceras).document
        val items = if (portada) doc.tarjetasCapitulos() else doc.tarjetas()
        return newHomePageResponse(request, items, !portada && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get("$mainUrl/buscar", params = mapOf("q" to query), headers = cabeceras).document
        return doc.tarjetas()
    }

    override suspend fun load(url: String): LoadResponse? {
        // Los items de "Ultimos capitulos" apuntan al capitulo: se sube a su ficha.
        var ficha = url
        if (url.contains("/ver/")) {
            val capitulo = app.get(url, headers = cabeceras).document
            val enlace = capitulo.selectFirst("a[href*='/anime/']")?.attr("href") ?: return null
            ficha = if (enlace.startsWith("http")) enlace else mainUrl + enlace
        }

        val doc = app.get(ficha, headers = cabeceras).document
        val titulo = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = doc.select("img").mapNotNull { img ->
            (img.attr("data-src").ifBlank { img.attr("src") }).takeIf { it.startsWith("http") }
        }.firstOrNull { it.contains("/assets/thumbs/") && !it.contains("capblank") }

        val sinopsis = doc.select("#tab-info p").map { it.text().trim() }
            .firstOrNull { it.length > 60 }

        val generos = doc.select("a[href*='/genero']").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()

        val anio = anioRegex.find(doc.selectFirst("#tab-info")?.text().orEmpty())?.value?.toIntOrNull()
        val idioma = idiomaDe(generos)

        val episodios = capitulos(doc, idioma)

        return newTvSeriesLoadResponse(titulo, ficha, TvType.Anime, episodios) {
            this.posterUrl = poster
            this.plot = sinopsis
            this.tags = generos
            this.year = anio
        }
    }

    /** El sitio marca el doblaje como genero: latino o castellano. */
    private fun idiomaDe(generos: List<String>): String {
        val plano = generos.joinToString(" ").lowercase()
        return when {
            plano.contains("latino") -> "LATINO"
            plano.contains("castellano") -> "CASTELLANO"
            else -> "SUB"
        }
    }

    private suspend fun capitulos(doc: Document, idioma: String): List<Episode> {
        val ajax = doc.selectFirst(".caplist")?.attr("data-ajax")?.takeIf { it.startsWith("http") }
            ?: return emptyList()
        val listado = primeraPaginacion(ajax) ?: return emptyList()

        val salida = mutableListOf<Episode>()
        var desplazamiento = 0
        var pagina = 1
        while (pagina <= 60) {
            val destino = if (pagina == 1) listado else "$listado?p=$pagina"
            val cuerpo = runCatching { app.get(destino, headers = cabeceras).text }.getOrNull() ?: break

            val caps = capsDe(cuerpo)
            if (caps.isEmpty()) break
            if (pagina == 1 && caps.firstOrNull()?.first == 0) desplazamiento = 1
            caps.forEach { (numero, enlace, miniatura) ->
                if (salida.any { it.data == "$enlace#$idioma" }) return@forEach
                val n = numero + desplazamiento
                salida.add(
                    newEpisode("$enlace#$idioma") {
                        this.name = "Episodio " + n
                        this.episode = n
                        this.posterUrl = miniatura
                    }
                )
            }
            if (caps.size < 50) break
            pagina++
        }
        return salida
    }

    /** ajax_pagination devuelve {"paginate_url": ".../ajax/caplist/<id>", ...}. */
    private suspend fun primeraPaginacion(ajax: String): String? {
        val meta = runCatching { app.get(ajax, headers = cabeceras).text }.getOrNull() ?: return null
        val limpio = meta.replace("\\/", "/")
        return Regex(""""paginate_url"\s*:\s*"([^"]+)"""").find(limpio)?.groupValues?.get(1)
    }

    private fun capsDe(json: String): List<Triple<Int, String, String?>> {
        val limpio = json.replace("\\/", "/")
        val regex = Regex(
            "\"episodio\"\\s*:\\s*(\\d+)\\s*,\\s*\"url\"\\s*:\\s*\"([^\"]+)\"(?:" +
                "\\s*,\\s*\"thumb\"\\s*:\\s*\"([^\"]+)\")?"
        )
        return regex.findAll(limpio).mapNotNull { m ->
            val numero = m.groupValues[1].toIntOrNull() ?: 0
            // Unos listados dan la url absoluta y otros relativa (/ver/...).
            val crudo = m.groupValues[2]
            val enlace = if (crudo.startsWith("http")) crudo else mainUrl + "/" + crudo.trimStart('/')
            val miniatura = m.groupValues[3].takeIf { it.isNotEmpty() }?.let {
                val completo = if (it.startsWith("http")) it else mainUrl + "/" + it.trimStart('/')
                retrato(completo)
            }
            if (!enlace.contains("/ver/")) null else Triple(numero, enlace, miniatura)
        }.toList()
    }

    /** Portada: tarjetas de capitulo reciente, con portada y etiqueta de episodio. */
    private suspend fun Document.tarjetasCapitulos(): List<SearchResponse> {
        val salida = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()
        val compuestas = capsDisponibles()

        // Solo la seccion "Ultimos capitulos": el resto de la portada tiene otros
        // enlaces (/ver/ del hero) que repetian la misma obra con titulos cortados.
        val seccion = select("section").firstOrNull { s ->
            s.selectFirst("h2")?.text()?.contains("ltimos cap", ignoreCase = true) == true
        } ?: return salida

        seccion.select("a[href]").forEach { enlace ->
            val href = enlace.attr("href")
            if (!href.contains("/ver/")) return@forEach
            val titulo = enlace.selectFirst(".card-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEach

            // Una entrada por obra: el sitio repite el mismo anime por cada
            // capitulo nuevo (Battle Spirits salia con los episodios 6, 5 y 3).
            val obra = href.substringAfter("/ver/").replace(Regex("-episodio-\\d+.*$"), "")
            if (!vistos.add(obra)) return@forEach

            val sitio = enlace.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") })
                    .takeIf { it.startsWith("http") && !it.contains("/img/anime.png") }
            }
            val numero = episodioEnUrl.find(href)?.groupValues?.get(1) ?: "1"
            val clave = obra + "-" + numero
            val poster = if (compuestas.contains(clave)) {
                "$CAPS_BASE/$clave.jpg?v=$CAPS_VER"
            } else {
                sitio?.let { retrato(it) }
            }

            salida.add(newAnimeSearchResponse(titulo, href, TvType.Anime) { this.posterUrl = poster })
        }
        return salida
    }

    private fun Document.tarjetas(): List<SearchResponse> {
        val salida = mutableListOf<SearchResponse>()
        select("a[href]").forEach { enlace ->
            val href = enlace.attr("href")
            if (!href.contains("/anime/")) return@forEach
            val titulo = enlace.selectFirst(".card-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            val completo = if (href.startsWith("http")) href else mainUrl + href
            if (salida.any { it.url == completo }) return@forEach
            val poster = enlace.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") })
                    .takeIf { it.startsWith("http") && !it.contains("/img/anime.png") }
            }
            salida.add(newAnimeSearchResponse(titulo, completo, TvType.Anime) { this.posterUrl = poster })
        }
        return salida
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val enlaceCapitulo = data.substringBefore("#")
        val idioma = data.substringAfter("#", "SUB").ifBlank { "SUB" }

        val doc = app.get(enlaceCapitulo, headers = cabeceras).document
        var alguno = false

        doc.select("button[data-player]").forEach { boton ->
            val codificado = boton.attr("data-player").trim()
            if (codificado.isEmpty()) return@forEach
            val embed = runCatching {
                String(Base64.decode(codificado, Base64.DEFAULT)).trim()
            }.getOrNull() ?: return@forEach
            if (!embed.startsWith("http")) return@forEach

            runCatching {
                loadExtractor(embed, "$mainUrl/", subtitleCallback) { video ->
                    alguno = true
                    @Suppress("DEPRECATION")
                    callback(
                        ExtractorLink(
                            source = video.source,
                            name = idioma + " \u00b7 " + video.name,
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
        }

        return alguno
    }
}
