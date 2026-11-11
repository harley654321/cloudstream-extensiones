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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Monoschinos (monoschinos.st): anime, donghua y peliculas, con audio original
 * subtitulado y tambien titulos con doblaje latino y castellano.
 *
 * Detalles del sitio que hay que respetar:
 * - Todo va por ?p=N (portada, emision, catalogo y sus filtros).
 * - Los capitulos se piden por AJAX (.caplist -> ajax_pagination -> caplist).
 *   Esos endpoints devuelven 403 si el User-Agent no es de navegador.
 * - El sitio guarda dos imagenes por obra: "portada" (apaisada, la de la fila
 *   de capitulos) e "imagen" (vertical, la del buscador y el directorio). Aqui
 *   se usa siempre la vertical, que es la que da la ficha.
 * - El numero de episodio de la portada es CSS, no existe en la imagen, asi que
 *   la fila "Ultimos capitulos" usa portadas compuestas en el repo (caps/ms).
 * - La red del sitio falla a ratos: toda peticion se reintenta hasta 3 veces.
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
    private val episodioEnUrl = Regex("""-episodio-(\d+)""")

    private companion object {
        /** Portadas compuestas con la etiqueta del capitulo (repo del proyecto). */
        const val CAPS_BASE =
            "https://raw.githubusercontent.com/harley654321/cloudstream-extensiones/main/caps/ms"

        /** Igual que DISENO en .github/scripts/caps_ms.py. */
        const val CAPS_VER = "2"
        const val CAPS_TTL = 60 * 60 * 1000L

        /** El sitio corta conexiones a ratos: se reintenta antes de rendirse. */
        const val REINTENTOS = 3

        @Volatile
        private var capsCache: Map<String, String>? = null

        @Volatile
        private var capsCacheAt = 0L
    }

    // ------------------------------------------------------------- red

    private suspend fun pedir(url: String, referer: String? = null): String? {
        repeat(REINTENTOS) { intento ->
            runCatching { return app.get(url, headers = cabeceras, referer = referer).text }
            // Sin kotlinx.coroutines en el classpath de la extension: espera corta
            // entre intentos, suficiente para que el sitio se recupere del corte.
            if (intento < REINTENTOS - 1) Thread.sleep(500L * (intento + 1))
        }
        return null
    }

    private suspend fun documento(url: String, referer: String? = null): Document? {
        val texto = pedir(url, referer) ?: return null
        return runCatching { Jsoup.parse(texto, url) }.getOrNull()
    }

    // --------------------------------------------------------- portada

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val esPortada = request.data == "$mainUrl/"
        val url = if (esPortada) mainUrl else request.data + page
        val doc = documento(url) ?: return newHomePageResponse(request, emptyList(), false)
        val items = if (esPortada) doc.tarjetasCapitulos() else doc.tarjetas()
        return newHomePageResponse(request, items, !esPortada && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val buscado = URLEncoder.encode(query, "UTF-8")
        val doc = documento("$mainUrl/buscar?q=$buscado") ?: return emptyList()
        return doc.tarjetas()
    }

    // ----------------------------------------------------------- ficha

    override suspend fun load(url: String): LoadResponse? {
        // Los items de "Ultimos capitulos" apuntan al capitulo: se sube a su ficha.
        var ficha = url
        if (url.contains("/ver/")) {
            val capitulo = documento(url) ?: return null
            val enlace = capitulo.selectFirst("a[href*='/anime/']")?.attr("href") ?: return null
            ficha = if (enlace.startsWith("http")) enlace else mainUrl + enlace
        }

        val html = pedir(ficha) ?: return null
        val doc = runCatching { Jsoup.parse(html, ficha) }.getOrNull() ?: return null
        val titulo = doc.selectFirst("h1")?.text()?.trim() ?: return null

        val imagenes = doc.select("img").mapNotNull { img ->
            (img.attr("data-src").ifBlank { img.attr("src") }).takeIf { it.startsWith("http") }
        }
        // La vertical de la ficha es la misma que se ve en buscador y directorio.
        val poster = imagenes.firstOrNull { it.contains("/assets/thumbs/imagen/") && !it.contains("capblank") }
            ?: imagenes.firstOrNull { it.contains("/assets/thumbs/") && !it.contains("capblank") }

        val sinopsis = doc.select("#tab-info p").map { it.text().trim() }
            .firstOrNull { it.length > 60 }

        val generos = doc.select("a[href*='/genero']").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()

        val anio = Regex("""(19|20)\d{2}""")
            .find(doc.selectFirst("#tab-info")?.text().orEmpty())?.value?.toIntOrNull()
        val idioma = idiomaDe(generos)

        val episodios = capitulos(ficha, doc, html, idioma, poster)

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

    // ------------------------------------------------------- capitulos

    private suspend fun capitulos(
        ficha: String,
        doc: Document,
        html: String,
        idioma: String,
        portada: String?
    ): List<Episode> {
        val salida = mutableListOf<Episode>()

        // Plan A: el data-ajax que trae la ficha. Plan B: el id del listado en el
        // HTML (por si el atributo cambia). Plan C: los enlaces /ver/ del HTML.
        var listado = listadoDeCaps(doc, html)
        if (listado != null && listado.contains("ajax_pagination")) {
            listado = primeraPaginacion(listado) ?: listado.replace("ajax_pagination", "caplist")
        }

        if (listado != null) {
            var desplazamiento = 0
            var pagina = 1
            while (pagina <= 60) {
                val destino = if (pagina == 1) listado else "$listado?p=$pagina"
                val cuerpo = pedir(destino) ?: break

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
                            // Algunas obras SI traen captura propia por capitulo
                            // (assets/img/serie/episodio/...). Si no la traen, se
                            // usa la portada de la obra, que es lo que hace la web.
                            this.posterUrl = posterCapitulo(miniatura, portada)
                        }
                    )
                }
                if (caps.size < 50) break
                pagina++
            }
        }

        // Ultimo recurso: lo que venga enlazado en la propia ficha.
        if (salida.isEmpty()) {
            Regex("""/ver/[a-z0-9-]*?episodio-(\d+)""").findAll(html).forEach { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@forEach
                val enlace = mainUrl + m.value
                if (salida.any { it.data == "$enlace#$idioma" }) return@forEach
                salida.add(newEpisode("$enlace#$idioma") {
                    this.name = "Episodio " + n
                    this.episode = n
                    this.posterUrl = portada
                })
            }
        }
        return salida
    }

    /** Listado de capitulos: atributo del .caplist o el id incrustado en el HTML. */
    private fun listadoDeCaps(doc: Document, html: String): String? {
        doc.selectFirst(".caplist")?.attr("data-ajax")
            ?.takeIf { it.startsWith("http") }?.let { return it }

        val caplist = Regex("/ajax/caplist/(\\d+)").find(html)
        if (caplist != null) return mainUrl + "/ajax/caplist/" + caplist.groupValues[1]

        val paginacion = Regex("/ajax/ajax_pagination/(\\d+)").find(html)
        if (paginacion != null) {
            return mainUrl + "/ajax/ajax_pagination/" + paginacion.groupValues[1]
        }
        return null
    }

    /** La captura propia del capitulo manda; si no hay, la portada de la obra. */
    private fun posterCapitulo(miniatura: String?, portada: String?): String? {
        val propia = miniatura?.takeIf { it.contains("/assets/img/serie/episodio/") }
        return propia ?: portada
    }

    /** ajax_pagination responde {"paginate_url": ".../ajax/caplist/<id>", ...}. */
    private suspend fun primeraPaginacion(ajax: String): String? {
        val meta = pedir(ajax) ?: return null
        val limpio = meta.replace("\\/", "/")
        return Regex(""""paginate_url\s*:\s*"([^"]+)"""").find(limpio)?.groupValues?.get(1)
    }

    /** Lee "caps":[{"episodio":N,"url":"..."}] sin depender de modelos. */
    /** Lee "caps":[{"episodio":N,"url":"...","thumb":"..."}] sin depender de modelos. */
    private fun capsDe(json: String): List<Triple<Int, String, String?>> {
        val limpio = json.replace("\\/", "/")
        val patron = "\"episodio\"\\s*:\\s*(\\d+)\\s*,\\s*\"url\"\\s*:\\s*\"([^\"]+)\"" +
            "(?:\\s*,\\s*\"thumb\"\\s*:\\s*\"([^\"]+)\")?"
        val regex = Regex(patron)
        return regex.findAll(limpio).mapNotNull { m ->
            val numero = m.groupValues[1].toIntOrNull() ?: 0
            val crudo = m.groupValues[2]
            val enlace = if (crudo.startsWith("http")) crudo else mainUrl + "/" + crudo.trimStart('/')
            val miniatura = m.groupValues[3].takeIf { it.isNotEmpty() }?.let {
                if (it.startsWith("http")) it else mainUrl + "/" + it.trimStart('/')
            }
            if (enlace.contains("/ver/")) Triple(numero, enlace, miniatura) else null
        }.toList()
    }

    // -------------------------------------------------------- tarjetas

    /**
     * Indice del repo: claves con portada compuesta y, para las que no se
     * pudieron componer, la direccion de su portada vertical (campo _cover).
     * Se refresca cada hora.
     */
    private suspend fun indiceCaps(): Map<String, String> {
        val ahora = System.currentTimeMillis()
        capsCache?.let { if (ahora - capsCacheAt < CAPS_TTL) return it }
        val nuevo = runCatching {
            val raiz = mapper.readTree(app.get("$CAPS_BASE/index.json?v=$CAPS_VER").text)
            val salida = mutableMapOf<String, String>()
            raiz.fieldNames().forEach { campo ->
                if (!campo.startsWith("_")) salida[campo] = ""
            }
            raiz.get("_cover")?.fields()?.forEach { (clave, valor) ->
                if (!salida.containsKey(clave)) salida[clave] = valor.asText()
            }
            salida
        }.getOrNull()
        if (nuevo != null) {
            capsCache = nuevo
            capsCacheAt = ahora
            return nuevo
        }
        return capsCache ?: emptyMap()
    }

    /**
     * Algunas obras no siguen el patron <slug>-<id> en la imagen vertical (hay
     * -T1- y -T2-), asi que esto es solo un respaldo: lo normal es que la
     * tarjeta use la portada compuesta del repo, que ya sale de la ficha.
     */
    private fun retrato(url: String): String {
        if (url.contains("/assets/thumbs/imagen/")) return url
        val m = Regex("""/portada/(.+?)-portada-(\d+)\.jpg""").find(url) ?: return url
        val cola = if (url.contains("?")) "?v=6" else ""
        return mainUrl + "/assets/thumbs/imagen/" + m.groupValues[1] + "-" +
            m.groupValues[2] + ".jpg.webp" + cola
    }

    /** Fila "Ultimos capitulos": una tarjeta por obra, con su numero en la portada. */
    private suspend fun Document.tarjetasCapitulos(): List<SearchResponse> {
        val salida = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()
        val indice = indiceCaps()

        // Solo esa seccion: el resto de la portada tiene enlaces /ver/ del hero
        // que repetian la misma obra con titulos cortados.
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

            val numero = episodioEnUrl.find(href)?.groupValues?.get(1) ?: "1"
            val clave = obra + "-" + numero
            // Portada compuesta si existe; si no, la vertical que guarda el
            // indice como respaldo; y si tampoco, la de la propia tarjeta.
            val respaldo = indice[clave]
            val poster = when {
                indice.containsKey(clave) && respaldo.isNullOrEmpty() ->
                    "$CAPS_BASE/$clave.jpg?v=$CAPS_VER"
                !respaldo.isNullOrEmpty() -> respaldo
                else -> enlace.selectFirst("img")?.let { img ->
                    val sitio = img.attr("data-src").ifBlank { img.attr("src") }
                    if (sitio.startsWith("http")) retrato(sitio) else null
                }
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
                val sitio = img.attr("data-src").ifBlank { img.attr("src") }
                sitio.takeIf { it.startsWith("http") && !it.contains("/img/anime.png") }
            }
            salida.add(newAnimeSearchResponse(titulo, completo, TvType.Anime) { this.posterUrl = poster })
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
        val enlaceCapitulo = data.substringBefore("#")
        val idioma = data.substringAfter("#", "SUB").ifBlank { "SUB" }

        val doc = documento(enlaceCapitulo) ?: return false
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
