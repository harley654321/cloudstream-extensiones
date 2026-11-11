package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

/**
 * Proveedor de prueba. No tiene catalogo real: sirve para comprobar desde la
 * propia app que esta extension esta cargada y que sus resolvedores devuelven
 * enlaces. Si los tres items dan servidores, todo esta en orden.
 */
class FoxPrueba : MainAPI() {
    override var mainUrl = "https://prueba.fox.local"
    override var name = "Fox Prueba"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie)

    private val pruebas = listOf(
        "Prueba Uqload" to "https://uqload.vc/embed-gdk2cg85jtby.html",
        "Prueba Vidara" to "https://vidara.to/e/pKZFR4ZqNWavx",
        "Prueba Byse" to "https://bysezoxexe.com/e/ycjtrxisct4t"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = pruebas.map { (titulo, embed) ->
            newMovieSearchResponse(titulo, mainUrl + "|" + embed, TvType.Movie) {
                this.posterUrl = "https://raw.githubusercontent.com/harley654321/cloudstream-extensiones/main/compiladas/icono.png"
            }
        }
        return newHomePageResponse(listOf(HomePageList("Resolvedores instalados", items)), false)
    }

    override suspend fun load(url: String): LoadResponse? {
        val embed = url.substringAfter("|", "")
        if (embed.isBlank()) return null
        val host = embed.substringAfter("://").substringBefore('/')
        return newMovieLoadResponse("Prueba " + host, url, TvType.Movie, url) {
            this.plot = "Item de prueba: se resuelve " + embed + " con los resolvedores de esta extension."
            this.tags = listOf("prueba", host)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = data.substringAfter("|", "")
        if (embed.isBlank()) return false
        var alguno = false
        runCatching {
            loadExtractor(embed, "$mainUrl/", subtitleCallback) { link ->
                alguno = true
                callback(link)
            }
        }
        return alguno
    }
}
