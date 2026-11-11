package com.foxcloud.monoschinos

import android.util.Base64
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

/**
 * Monoschinos (monoschinos.st): anime en subtitulado espanol.
 * - Portada y catalogo paginan de verdad con ?p=N.
 * - La ficha carga los capitulos por AJAX: .caplist trae la direccion del
 *   listado, que responde JSON con numero, enlace y miniatura de cada uno.
 * - El reproductor de cada capitulo trae sus servidores en base64 dentro de
 *   button[data-player]; se resuelven con la lista global de la app, asi que
 *   los hosts que falten los aporta la extension de resolvedores.
 */
class MonoschinosProvider : MainAPI() {
    override var mainUrl = "https://monoschinos.st"
    override var name = "Monoschinos"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/emision?p=" to "En emision",
        "$mainUrl/animes?p=" to "Catalogo"
    )

    private val anioRegex = Regex("""(19|20)\d{2}""")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val items = doc.tarjetas()
        return newHomePageResponse(request, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get("$mainUrl/buscar", params = mapOf("q" to query)).document
        return doc.tarjetas()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val titulo = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = doc.select("img").mapNotNull { img ->
            (img.attr("data-src").ifBlank { img.attr("src") }).takeIf { it.startsWith("http") }
        }.firstOrNull { it.contains("/assets/thumbs/") && !it.contains("capblank") }

        val sinopsis = doc.select("#tab-info p").map { it.text().trim() }
            .firstOrNull { it.length > 60 }

        val generos = doc.select("a[href*='/genero']").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()

        val anio = anioRegex.find(doc.selectFirst("#tab-info")?.text().orEmpty())?.value?.toIntOrNull()

        val episodios = capitulos(doc)

        return newTvSeriesLoadResponse(titulo, url, TvType.Anime, episodios) {
            this.posterUrl = poster
            this.plot = sinopsis
            this.tags = generos
            this.year = anio
        }
    }

    /** Listado de capitulos: .caplist da el endpoint, que responde JSON paginado. */
    private suspend fun capitulos(doc: Document): List<Episode> {
        val listado = doc.selectFirst(".caplist")?.attr("data-ajax")?.takeIf { it.startsWith("http") }
            ?: return emptyList()

        val salida = mutableListOf<Episode>()
        // Algunos animes numeran desde 0 (0..293): se detecta en la primera
        // pagina y se corre toda la lista uno para que el 1 sea el primero.
        var desplazamiento = 0
        var pagina = 1
        while (pagina <= 60) {
            val cuerpo = runCatching {
                app.get(if (pagina == 1) listado else "$listado?p=$pagina").text
            }.getOrNull() ?: break

            val caps = capsDe(cuerpo)
            if (caps.isEmpty()) break
            if (pagina == 1 && caps.firstOrNull()?.first == 0) desplazamiento = 1
            caps.forEach { (numero, enlace, miniatura) ->
                if (salida.any { it.data == enlace }) return@forEach
                val n = numero + desplazamiento
                salida.add(
                    newEpisode(enlace) {
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

    /** Lee "caps":[{"episodio":1,"url":"...","thumb":"..."}] sin depender de modelos. */
    private fun capsDe(json: String): List<Triple<Int, String, String?>> {
        val limpio = json.replace("\\/", "/")
        val regex = Regex(""""episodio"\s*:\s*(\d+)\s*,\s*"url"\s*:\s*"([^"]+)"(?:\s*,\s*"thumb"\s*:\s*"([^"]+)")?""")
        return regex.findAll(limpio).mapNotNull { m ->
            val numero = m.groupValues[1].toIntOrNull() ?: 0
            val enlace = m.groupValues[2]
            if (!enlace.startsWith("http")) null
            else Triple(numero, enlace, m.groupValues[3].takeIf { it.startsWith("http") })
        }.toList()
    }

    private fun Document.tarjetas(): List<SearchResponse> {
        val salida = mutableListOf<SearchResponse>()
        select("a[href]").forEach { enlace ->
            val titulo = enlace.selectFirst(".card-title")?.text()?.trim()
                ?: enlace.selectFirst(".card-title")?.wholeText()?.trim()
                ?: return@forEach
            if (titulo.isEmpty()) return@forEach
            val href = enlace.attr("href")
            if (!href.contains("/anime/")) return@forEach
            val completo = if (href.startsWith("http")) href else mainUrl + href
            if (salida.any { it.url == completo }) return@forEach
            val poster = enlace.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") }).takeIf { it.startsWith("http") && !it.contains("/img/anime.png") }
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
        val doc = app.get(data).document
        var alguno = false

        doc.select("button[data-player]").forEach { boton ->
            val codificado = boton.attr("data-player").trim()
            if (codificado.isEmpty()) return@forEach
            val embed = runCatching {
                String(Base64.decode(codificado, Base64.DEFAULT)).trim()
            }.getOrNull() ?: return@forEach
            if (!embed.startsWith("http")) return@forEach

            // El sitio es solo subtitulado: todo va marcado como SUB.
            runCatching {
                loadExtractor(embed, "$mainUrl/", subtitleCallback) { video ->
                    alguno = true
                    @Suppress("DEPRECATION")
                    callback(
                        ExtractorLink(
                            source = video.source,
                            name = "SUB \u00b7 " + video.name,
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
