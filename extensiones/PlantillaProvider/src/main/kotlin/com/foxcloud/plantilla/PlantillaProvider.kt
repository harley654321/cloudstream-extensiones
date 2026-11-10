package com.foxcloud.plantilla

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * PLANTILLA de proveedor para la fabrica de extensiones.
 *
 * Sirve como estructura de referencia y como prueba de humo: usa peliculas
 * abiertas de la Blender Foundation (licencia CC-BY) enlazadas directamente,
 * asi que se puede instalar y reproducir sin depender de ningun sitio externo.
 *
 * Para una extension real:
 *   1. Cambia mainUrl por el dominio de la web.
 *   2. Sustituye el catalogo fijo por scraping con app.get(url).document (Jsoup)
 *      o por la API JSON del sitio con app.get(url).parsedSafe<T>().
 *   3. En loadLinks, cambia el enlace directo por loadExtractor(url, ...) o por
 *      los extractores propios que registres en el plugin.
 */
class PlantillaProvider : MainAPI() {
    override var mainUrl = "https://download.blender.org"
    override var name = "Plantilla demo"
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true
    override val hasQuickSearch = true

    override val mainPage = listOf(
        MainPageData("Peliculas abiertas", "demo", false)
    )

    private data class Pelicula(
        val titulo: String,
        val sinopsis: String,
        val anio: Int,
        val slug: String,
        val video: String
    )

    private val catalogo = listOf(
        Pelicula(
            titulo = "Sintel (trailer oficial)",
            sinopsis = "Trailer del corto abierto Sintel, de la Blender Foundation.",
            anio = 2010,
            slug = "sintel",
            video = "$mainUrl/durian/trailer/sintel_trailer-720p.mp4"
        ),
        Pelicula(
            titulo = "Elephants Dream",
            sinopsis = "Primer corto abierto de la Blender Foundation (2006), codigo y arte libres.",
            anio = 2006,
            slug = "elephants-dream",
            video = "$mainUrl/ED/ED_1024.avi"
        )
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(
            request.name,
            catalogo.map { it.toSearchResponse() },
            hasNext = false
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        return catalogo
            .filter { it.titulo.contains(query, ignoreCase = true) }
            .map { it.toSearchResponse() }
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val pelicula = catalogo.firstOrNull { url.endsWith("/${it.slug}") } ?: return null
        return newMovieLoadResponse(
            name = pelicula.titulo,
            url = url,
            type = TvType.Movie,
            dataUrl = pelicula.video
        ) {
            this.year = pelicula.anio
            this.plot = pelicula.sinopsis
            this.tags = listOf("abierto", "demo")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private fun Pelicula.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            name = titulo,
            url = "$mainUrl/$slug",
            type = TvType.Movie
        ) {
            this.year = anio
        }
    }
}
