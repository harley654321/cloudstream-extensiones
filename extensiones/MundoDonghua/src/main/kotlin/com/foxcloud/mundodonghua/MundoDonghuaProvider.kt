package com.foxcloud.mundodonghua

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Mundo Donghua (mundodonghua.com): animacion china en espanol.
 *
 * Lo que hay que saber del sitio:
 * - Listas con ?page=N (catalogo, finalizados, ultimos episodios). En emision y
 *   recopilados solo tienen la primera pagina.
 * - Buscador por /?s=<texto>. Devuelve obras y tambien tarjetas de episodio.
 * - Ficha /donghua/<slug> con los episodios ya en el HTML (.md-episode-item,
 *   data-ep), sin paginacion y en orden descendente.
 * - Los reproductores NO vienen en un iframe: cada episodio trae cuatro bloques
 *   de JavaScript empaquetado (el clasico packer eval(function(p,a,c,k,e,d)) y
 *   de ahi sale la direccion de cada servidor. Aqui se desempaquetan sin
 *   ejecutar nada y se pasan a la lista global de extractores.
 * - Servidores vistos en toda la muestra: bysekoze (Byse), voe.sx, vidhidepro y
 *   embedwish. CloudStream ya resuelve los tres ultimos; Byse lo pone
 *   FoxResolvedores.
 */
class MundoDonghuaProvider : MainAPI() {
    override var mainUrl = "https://www.mundodonghua.com"
    override var name = "Mundo Donghua"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cabeceras = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/lista-episodios?page=" to "Ultimos episodios",
        "$mainUrl/lista-donghuas-emision" to "En emision",
        "$mainUrl/lista-donghuas-finalizados?page=" to "Finalizados",
        "$mainUrl/lista-donghuas?page=" to "Todas",
        "$mainUrl/lista-donghuas-recopilados" to "Recopiladas"
    )

    private companion object {
        const val REINTENTOS = 3
        val PACKER = Regex(
            """eval\(function\(p,a,c,k,e,d\).*?\}\('((?:[^'\\]|\\.)*)',(\d+),(\d+),'((?:[^'\\]|\\.)*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val URL_EN_TEXTO = Regex("""https?://[^"'\s\\]+""")
    }

    // ------------------------------------------------------------- red

    private suspend fun pedir(url: String, referer: String? = null): String? {
        repeat(REINTENTOS) { intento ->
            runCatching { return app.get(url, headers = cabeceras, referer = referer).text }
            if (intento < REINTENTOS - 1) Thread.sleep(500L * (intento + 1))
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

    private fun obraDe(href: String): String {
        val completo = absoluto(href)
        return if (completo.contains("/ver/")) {
            mainUrl + "/donghua/" + completo.substringAfter("/ver/").substringBefore("/")
        } else completo
    }

    // --------------------------------------------------------- portada

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val paginada = request.data.contains("?page=")
        val url = if (paginada) request.data + page else request.data
        val doc = documento(url) ?: return newHomePageResponse(request, emptyList(), false)
        val items = doc.tarjetas()
        return newHomePageResponse(request, items, paginada && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val buscado = URLEncoder.encode(query, "UTF-8")
        val doc = documento("$mainUrl/?s=$buscado") ?: return emptyList()
        return doc.tarjetas()
    }

    // ----------------------------------------------------------- ficha

    override suspend fun load(url: String): LoadResponse? {
        val ficha = obraDe(url)
        val doc = documento(ficha) ?: return null
        val titulo = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val poster = doc.selectFirst(".md-detail-poster img")?.attr("src")
            ?.takeIf { it.isNotBlank() }?.let { absoluto(it) }
            ?: doc.select("img").mapNotNull { it.attr("src") }
                .firstOrNull { it.contains("/thumbs/") && !it.contains("bg_") }
                ?.let { absoluto(it) }

        val texto = doc.selectFirst(".md-detail-info")?.text().orEmpty()
        val anio = Regex("""(19|20)\d{2}""").find(texto)?.value?.toIntOrNull()
        val generos = doc.select("a[href*='/genero/']").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()

        val sinopsis = doc.selectFirst(".md-detail-synopsis")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }

        val episodios = episodios(doc, poster)

        return newTvSeriesLoadResponse(titulo, ficha, TvType.Anime, episodios) {
            this.posterUrl = poster
            this.plot = sinopsis
            this.tags = generos
            this.year = anio
        }
    }

    /** Los episodios ya vienen en el HTML, en orden descendente. */
    private fun episodios(doc: Document, portada: String?): List<Episode> {
        val salida = mutableListOf<Episode>()
        doc.select(".md-episode-item").forEach { item ->
            val href = item.selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: return@forEach
            val etiqueta = item.selectFirst("h5")?.text()?.trim()
            val numero = item.attr("data-ep").toIntOrNull()
                ?: Regex("""(\d+)\s*$""").find(etiqueta.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val miniatura = item.selectFirst("img")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { absoluto(it) } ?: portada
            salida.add(
                newEpisode(absoluto(href)) {
                    this.name = if (numero != null) "Episodio $numero" else (etiqueta ?: "Episodio")
                    this.episode = numero
                    this.posterUrl = miniatura
                }
            )
        }
        return salida.sortedBy { it.episode ?: 0 }
    }

    // -------------------------------------------------------- tarjetas

    private fun Document.tarjetas(): List<SearchResponse> {
        val salida = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()

        select(".md-card").forEach { tarjeta ->
            val enlace = tarjeta.selectFirst("a[href]")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: return@forEach
            if (!enlace.contains("/donghua/") && !enlace.contains("/ver/")) return@forEach

            val titulo = tarjeta.selectFirst(".md-card-title")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@forEach
            val poster = tarjeta.selectFirst("img")?.let { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                if (src.isBlank()) null else absoluto(src)
            }

            val obra = obraDe(enlace)
            if (!vistos.add(obra)) return@forEach

            // Las tarjetas de episodio traen "Obra Episodio N": se queda el titulo
            // de la obra para que la fila sea de series, no de episodios sueltos.
            val nombre = titulo
                .replace(Regex("""\s+Episodio\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
                .trim().ifEmpty { titulo }

            salida.add(newAnimeSearchResponse(nombre, obra, TvType.Anime) { this.posterUrl = poster })
        }
        return salida
    }

    // --------------------------------------------------------- enlaces

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = pedir(data) ?: return false
        val doc = runCatching { Jsoup.parse(html, data) }.getOrNull()

        val servidores = linkedSetOf<String>()
        servidores.addAll(embedsDe(html))
        doc?.select("iframe[src]")?.forEach { marco ->
            marco.attr("src").takeIf { it.startsWith("http") }?.let { servidores.add(it) }
        }

        var alguno = false
        servidores.forEach { embed ->
            runCatching {
                loadExtractor(embed, data, subtitleCallback) { enlace ->
                    alguno = true
                    callback(enlace)
                }
            }
        }
        return alguno
    }

    /**
     * Desempaqueta los bloques eval(function(p,a,c,k,e,d){...}) y devuelve las
     * direcciones que quedan dentro. El packer cambia cada token por la palabra
     * de su indice en base `a`, asi que se deshace al reves: de la ultima a la
     * primera, para no romper los indices.
     */
    private fun embedsDe(html: String): List<String> {
        val salida = linkedSetOf<String>()
        PACKER.findAll(html).forEach { bloque ->
            var codigo = bloque.groupValues[1]
            val base = bloque.groupValues[2].toIntOrNull() ?: 62
            val palabras = bloque.groupValues[4].split("|")
            for (i in palabras.indices.reversed()) {
                val palabra = palabras[i]
                if (palabra.isEmpty()) continue
                codigo = Regex("""\b""" + digito(i, base) + """\b""")
                    .replace(codigo) { palabra }
            }
            URL_EN_TEXTO.findAll(codigo).forEach { salida.add(it.value) }
        }
        return salida.toList()
    }

    private fun digito(valor: Int, base: Int): String {
        var numero = valor
        val texto = StringBuilder()
        while (numero >= base) {
            texto.insert(0, simbolo(numero % base))
            numero /= base
        }
        texto.insert(0, simbolo(numero))
        return texto.toString()
    }

    /** El packer usa 0-9, luego a-z (10..35) y luego A-Z (36..61). */
    private fun simbolo(valor: Int): String = when {
        valor > 35 -> (valor + 29).toChar().toString()
        valor < 10 -> valor.toString()
        else -> (valor + 87).toChar().toString()
    }
}
