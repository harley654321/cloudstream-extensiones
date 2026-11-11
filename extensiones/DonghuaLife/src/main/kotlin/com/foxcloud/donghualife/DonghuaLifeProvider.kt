package com.foxcloud.donghualife

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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * DonghuaLife (donghualife.com): donghua subtitulado en espanol.
 *
 * Lo que hay que saber del sitio (comprobado navegando la web entera):
 * - Es una web Next.js. Las paginas llegan ya dibujadas en el HTML: las
 *   tarjetas son enlaces normales, no hace falta navegador ni JavaScript.
 * - Portada con filas "Ultimos episodios", "Estrenos VIP", "Te recomendamos" y
 *   "Tendencias", y catalogos de verdad: /series?page=N (15 paginas, 24 por
 *   pagina), /peliculas?page=N (2 paginas), /rankings (10 obras) y
 *   /ultimos-episodios, que trae 88 capitulos en UNA sola pagina.
 * - /ultimos-episodios IGNORA el parametro ?page= por completo: se probaron
 *   ?page=2, ?page=3, ?pagina=2, ?p=2, ?offset=88 y ?page=2&limit=88 y las
 *   seis devuelven exactamente los mismos 88 capitulos. Si se pidiera la
 *   pagina 2 se repetiria la 1, por eso esa fila tiene una sola pagina.
 * - /genres solo tiene cinco generos y cada uno con una serie, asi que no
 *   merece fila propia.
 * - /vip no lista reproducciones (es una pagina informativa de la cuenta),
 *   por eso tampoco tiene fila.
 * - Buscador: /series?q=<texto> para series y /peliculas?q=<texto> para
 *   peliculas. Devuelve obras, no capitulos sueltos.
 * - Ficha de serie /series/<slug>: el HTML trae el titulo, la sinopsis y la
 *   lista COMPLETA de temporadas (Doupo Cangqiong: 13, incluidas especiales y
 *   peliculas) pero solo dibuja los capitulos de la primera. Los de las demas
 *   salen de /api/series/<slug>/seasons/<temporada>/episodes (JSON con id,
 *   title, number e image). La direccion de cada capitulo se arma como
 *   /watch/<temporada>-<numero>.
 * - Reproductores: cada pagina de capitulo (o de pelicula) lleva dentro un
 *   arreglo "sources" con una entrada por servidor (R2, Rumble, Dailymotion,
 *   Odysee, Ok.ru) y un token cifrado. La direccion real sale de un POST a
 *   /api/player/source con ese token: para R2 devuelve /api/r2-media?u=... (el
 *   master m3u8 propio, hasta 4K) y para los demas devuelve el embed.
 * - El m3u8 de R2 se pide sin galletas ni token de sesion y responde 200.
 * - Subtitulos: /api/subtitles?episodeId=<id> da la lista y
 *   /api/subtitles/<id> devuelve el .ass ENVUELTO EN JSON. No existe ninguna
 *   ruta que sirva el archivo crudo (?raw=1, .ass y /file dan 404 o el mismo
 *   JSON), asi que no se pueden entregar como pista externa sin romper la app.
 *
 * Aqui NO se resuelve ningun reproductor: esta extension solo consigue las
 * direcciones y se las entrega a la lista global de extractores. Dailymotion
 * (geo.dailymotion.com) y el propio HLS se resuelven en la app; Rumble y OK.ru
 * necesitan FoxResolvedores.
 */
class DonghuaLifeProvider : MainAPI() {
    override var mainUrl = "https://donghualife.com"
    override var name = "DonghuaLife"
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val cabeceras = mapOf(
        "User-Agent" to AGENTE,
        "Accept" to "text/html,application/xhtml+xml",
        "Accept-Language" to "es-ES,es;q=0.9"
    )

    private val cabecerasApi = mapOf(
        "User-Agent" to AGENTE,
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json"
    )

    /**
     * Filas de la portada. Solo llevan "?page=" las que de verdad paginan en
     * el servidor: /ultimos-episodios y /rankings devuelven siempre lo mismo.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/ultimos-episodios" to "Últimos capítulos",
        "$mainUrl/series?page=" to "Series",
        "$mainUrl/peliculas?page=" to "Películas",
        "$mainUrl/rankings" to "Tendencias"
    )

    private companion object {
        const val AGENTE = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

        const val REINTENTOS = 3

        /**
         * Tope duro para resolver un capitulo entero: o sale algo en segundos,
         * o la app dice que no hay enlaces. Nunca se queda girando.
         */
        const val TOTAL_MS = 18_000L

        /** Rutas que son tarjeta en un catalogo. */
        val RUTA_TARJETA = Regex("""^/(watch|series|peliculas)/""")

        /**
         * Cada temporada de la ficha, tal como viene en el HTML:
         * {"slug":"doupo-cangqiong-2","label":"Temporada 2", ... ,
         *  "isSpecial":false,"episodeCount":12, ...}
         */
        val TEMPORADA = Regex(
            """"slug":"([^"]+)","label":"([^"]+)","coverImage":"([^"]*)","isSpecial":(true|false),"episodeCount":(\d+)"""
        )
    }

    // ---------------------------------------------------------------- red

    private suspend fun pedir(
        url: String,
        referer: String? = null,
        intentos: Int = REINTENTOS
    ): String? {
        repeat(intentos) { intento ->
            runCatching { return app.get(url, headers = cabeceras, referer = referer).text }
            if (intento < intentos - 1) Thread.sleep(500L * (intento + 1))
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

    /**
     * En los catalogos la portada viene dentro del optimizador de Next
     * (/_next/image?url=%2Fuploads%2F...&w=...), asi que se saca la direccion
     * real y se pide tal cual la sirve el sitio, igual que en las demas
     * extensiones de la fabrica: sin intermediarios ni reducciones.
     */
    private fun imagen(img: Element): String? {
        val directa = img.attr("src").ifBlank { img.attr("data-src") }
        if (directa.isNotBlank() && !directa.startsWith("data:")) return absoluto(directa)

        val conjunto = img.attr("srcSet").ifBlank { img.attr("srcset") }
        if (conjunto.isBlank()) return null
        val elegida = conjunto.split(",")
            .map { it.trim().substringBefore(" ") }
            .lastOrNull { it.isNotBlank() } ?: return null

        val interno = Regex("""[?&]url=([^&]+)""").find(elegida)?.groupValues?.get(1)
        val ruta = if (interno != null) {
            runCatching { URLDecoder.decode(interno, "UTF-8") }.getOrDefault(interno)
        } else elegida
        return absoluto(ruta)
    }

    // ------------------------------------------------------------ catalogo

    /** Convierte las tarjetas de una pagina de catalogo en resultados. */
    private fun tarjetas(doc: Document): List<SearchResponse> {
        val salida = mutableListOf<SearchResponse>()
        val vistos = mutableSetOf<String>()

        doc.select("a[href]").forEach { enlace ->
            val href = enlace.attr("href")
            if (!RUTA_TARJETA.containsMatchIn(href)) return@forEach

            val destino = absoluto(href.split("?")[0])
            if (!vistos.add(destino)) return@forEach

            val img = enlace.selectFirst("img") ?: return@forEach
            val titulo = img.attr("alt").trim().ifBlank {
                enlace.selectFirst("p")?.text()?.trim().orEmpty()
            }.ifBlank { return@forEach }

            val texto = enlace.text()
            val capitulo = Regex("""Cap[ií]tulo\s+(\d+)""", RegexOption.IGNORE_CASE)
                .find(texto)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\bEP\s*(\d+)""").find(texto)?.groupValues?.get(1)?.toIntOrNull()

            val tipo = if (href.startsWith("/peliculas/")) TvType.AnimeMovie else TvType.Anime
            val nombre = if (href.startsWith("/watch/") && capitulo != null) {
                "$titulo - Cap. $capitulo"
            } else titulo

            salida.add(newAnimeSearchResponse(nombre, destino, tipo) {
                this.posterUrl = imagen(img)
            })
        }
        return salida
    }

    /**
     * Hay pagina siguiente si la paginacion del sitio ofrece un numero mayor
     * que el actual. En la ultima pagina no lo hay (comprobado en
     * /series?page=15 y /peliculas?page=2).
     */
    private fun haySiguiente(doc: Document, page: Int): Boolean {
        val mayor = doc.select("a[href]").mapNotNull { enlace ->
            Regex("""[?&]page=(\d+)""").find(enlace.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return mayor > page
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data
        // Las filas sin "?page=" solo tienen la primera pagina.
        if (page > 1 && !base.contains("?page=")) {
            return newHomePageResponse(request, emptyList(), false)
        }
        val url = if (base.contains("?page=")) "$base$page" else base

        val doc = documento(url) ?: return newHomePageResponse(request, emptyList(), false)
        val items = tarjetas(doc)
        val siguiente = items.isNotEmpty() && base.contains("?page=") && haySiguiente(doc, page)
        return newHomePageResponse(request, items, siguiente)
    }

    // ----------------------------------------------------------- buscador

    override suspend fun search(query: String): List<SearchResponse> {
        val buscado = URLEncoder.encode(query, "UTF-8")
        val series = runCatching { documento("$mainUrl/series?q=$buscado") }.getOrNull()
            ?.let { tarjetas(it) }.orEmpty()
        val peliculas = runCatching { documento("$mainUrl/peliculas?q=$buscado") }.getOrNull()
            ?.let { tarjetas(it) }.orEmpty()

        // En el buscador solo van obras: si una fila deja caer un capitulo, se
        // queda la serie a la que pertenece.
        return (series + peliculas)
            .map { resultado ->
                if (resultado.url.contains("/watch/")) {
                    resultado.name.replace(Regex("""\s*-\s*Cap\.\s*\d+$"""), "").let { limpio ->
                        newAnimeSearchResponse(limpio, resultado.url, TvType.Anime) {
                            this.posterUrl = resultado.posterUrl
                        }
                    }
                } else resultado
            }
            .distinctBy { it.url }
    }

    // -------------------------------------------------------------- ficha

    private data class Temporada(
        val slug: String,
        val label: String,
        val portada: String?,
        val especial: Boolean,
        val capitulos: Int
    )

    private fun temporadas(crudo: String): List<Temporada> {
        val limpio = crudo.replace("\\\"", "\"")
        return TEMPORADA.findAll(limpio).map { m ->
            Temporada(
                slug = m.groupValues[1],
                label = m.groupValues[2].ifBlank { "Temporada" },
                portada = m.groupValues[3].takeIf { it.isNotBlank() }?.let { absoluto(it) },
                especial = m.groupValues[4].toBoolean(),
                capitulos = m.groupValues[5].toIntOrNull() ?: 0
            )
        }.distinctBy { it.slug }.toList()
    }

    private suspend fun capitulosDe(serie: String, temporada: Temporada): List<JSONObject> {
        val cuerpo = pedir("$mainUrl/api/series/$serie/seasons/${temporada.slug}/episodes")
            ?: return emptyList()
        val lista = runCatching { JSONObject(cuerpo).optJSONArray("episodes") }.getOrNull()
            ?: return emptyList()
        return (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }
    }

    private suspend fun ficha(url: String): LoadResponse? {
        val crudo = pedir(url) ?: return null
        val doc = runCatching { Jsoup.parse(crudo, url) }.getOrNull() ?: return null

        val serie = url.substringAfter("/series/").substringBefore("?").trim('/')
        if (serie.isBlank()) return null

        val titulo = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { absoluto(it) }
        val sinopsis = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }

        val limpio = crudo.replace("\\\"", "\"")
        val generos = Regex(""""genre":\[([^\]]*)]""").find(limpio)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }.orEmpty()
        val anio = Regex(""""(?:year|releaseYear)":(\d{4})""").find(limpio)
            ?.groupValues?.get(1)?.toIntOrNull()

        val listaTemporadas = temporadas(crudo)
        if (listaTemporadas.isEmpty()) {
            // Sin temporadas legibles solo queda la primera, si existe.
            val unica = Temporada("$serie-1", "Temporada 1", poster, false, 0)
            val capitulos = capitulosDe(serie, unica)
            if (capitulos.isEmpty()) return null
            val episodios = capitulos.mapNotNull { ep ->
                val numero = ep.optInt("number", 0)
                if (numero <= 0) return@mapNotNull null
                newEpisode("$mainUrl/watch/${unica.slug}-$numero") {
                    this.name = ep.optString("title").ifBlank { "Episodio $numero" }
                    this.episode = numero
                    this.posterUrl = ep.optString("image").takeIf { it.isNotBlank() }
                        ?.let { absoluto(it) } ?: poster
                }
            }
            return newTvSeriesLoadResponse(titulo, url, TvType.Anime, episodios) {
                this.posterUrl = poster
                this.plot = sinopsis
                this.tags = generos
                this.year = anio
            }
        }

        // Todas las temporadas a la vez: Doupo Cangqiong tiene 13.
        val descargadas = coroutineScope {
            listaTemporadas.map { temp -> async { temp to capitulosDe(serie, temp) } }
                .map { it.await() }
        }

        val episodios = mutableListOf<Episode>()
        descargadas.forEachIndexed { indice, (temp, capitulos) ->
            val corta = temp.label.replace("Temporada ", "T").ifBlank {
                if (temp.especial) "Especial" else "T${indice + 1}"
            }
            for (ep in capitulos) {
                val numero = ep.optInt("number", 0)
                if (numero <= 0) continue
                val suelto = ep.optString("title").ifBlank { "Episodio $numero" }
                val nombre = if (listaTemporadas.size == 1) suelto else "$corta · $suelto"
                val propio = ep.optString("image").takeIf { it.isNotBlank() }?.let { absoluto(it) }
                val vip = ep.optBoolean("isVipOnly", false)

                episodios.add(
                    newEpisode("$mainUrl/watch/${temp.slug}-$numero") {
                        this.name = if (vip) "$nombre (VIP)" else nombre
                        this.episode = if (listaTemporadas.size == 1) numero else indice * 1000 + numero
                        this.posterUrl = propio ?: temp.portada ?: poster
                    }
                )
            }
        }

        if (episodios.isEmpty()) return null

        return newTvSeriesLoadResponse(titulo, url, TvType.Anime, episodios) {
            this.posterUrl = poster
            this.plot = sinopsis
            this.tags = generos
            this.year = anio
        }
    }

    private suspend fun pelicula(url: String): LoadResponse? {
        val doc = documento(url) ?: return null
        val titulo = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { absoluto(it) }
        val sinopsis = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }

        return newMovieLoadResponse(titulo, url, TvType.AnimeMovie, url) {
            this.posterUrl = poster
            this.plot = sinopsis
        }
    }

    private suspend fun capituloSuelto(url: String): LoadResponse? {
        val doc = documento(url) ?: return null
        val titular = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { absoluto(it) }
        val numero = Regex("""Cap(?:[ií]tulo|\.)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(titular)?.groupValues?.get(1)?.toIntOrNull()

        val unico = listOf(
            newEpisode(url) {
                this.name = titular
                this.episode = numero
                this.posterUrl = poster
            }
        )
        return newTvSeriesLoadResponse(titular, url, TvType.Anime, unico) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? = when {
        url.contains("/watch/") -> capituloSuelto(url)
        url.contains("/peliculas/") -> pelicula(url)
        url.contains("/series/") -> ficha(url)
        else -> null
    }

    // ------------------------------------------------------------ enlaces

    /**
     * No se resuelve nada aqui. Se le pide al sitio la direccion de cada
     * servidor con su token, todas a la vez y con tope de tiempo, y se entregan
     * a la lista global de extractores.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching { app.get(data, headers = cabeceras).text }.getOrNull() ?: return false
        val lista = arregloFuentes(html) ?: return false
        if (lista.length() == 0) return false

        val encontrado = AtomicBoolean(false)
        val emitidos = Collections.synchronizedSet(mutableSetOf<String>())

        withTimeoutOrNull(TOTAL_MS) {
            coroutineScope {
                (0 until lista.length()).map { indice ->
                    async {
                        val fuente = lista.optJSONObject(indice) ?: return@async
                        if (!fuente.optBoolean("isWorking", true)) return@async
                        val token = fuente.optString("token").takeIf { it.isNotBlank() } ?: return@async
                        val etiqueta = fuente.optString("label")
                            .ifBlank { fuente.optString("provider") }
                            .ifBlank { "Servidor" }

                        val direccion = resolver(token, data) ?: return@async
                        if (!emitidos.add(direccion)) return@async

                        // Lo que devuelve una ruta propia (el master m3u8 de R2)
                        // se entrega como HLS; lo que es un embed va a los
                        // extractores de la app y de FoxResolvedores.
                        if (direccion.startsWith("$mainUrl/")) {
                            encontrado.set(true)
                            callback(
                                newExtractorLink("DonghuaLife · $etiqueta", direccion, ExtractorLinkType.M3U8) {
                                    this.referer = data
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        } else {
                            runCatching {
                                loadExtractor(direccion, data, subtitleCallback) { enlace ->
                                    if (emitidos.add(enlace.url.trim())) {
                                        encontrado.set(true)
                                        callback(enlace)
                                    }
                                }
                            }
                        }
                    }
                }.forEach { it.await() }
            }
        }
        return encontrado.get()
    }

    /**
     * Saca el arreglo "sources" de dentro del HTML. Viene escapado dentro de un
     * script de Next, por eso se recorta respetando las cadenas.
     */
    private fun arregloFuentes(html: String): JSONArray? {
        val limpio = html.replace("\\\"", "\"")
        val marca = limpio.indexOf("\"sources\":[")
        if (marca < 0) return null
        val inicio = limpio.indexOf('[', marca)
        if (inicio < 0) return null

        var profundidad = 0
        var enCadena = false
        var escape = false
        var i = inicio
        while (i < limpio.length) {
            val c = limpio[i]
            if (enCadena) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> enCadena = false
                }
            } else {
                when (c) {
                    '"' -> enCadena = true
                    '[' -> profundidad++
                    ']' -> {
                        profundidad--
                        if (profundidad == 0) {
                            val crudo = limpio.substring(inicio, i + 1)
                            return runCatching { JSONArray(crudo) }.getOrNull()
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    /** POST /api/player/source con el token de la fuente: devuelve la direccion real. */
    private suspend fun resolver(token: String, referer: String): String? {
        val cuerpo = runCatching {
            app.post(
                "$mainUrl/api/player/source",
                headers = cabecerasApi + mapOf("Referer" to referer, "Origin" to mainUrl),
                json = mapOf("token" to token)
            ).text
        }.getOrNull() ?: return null

        val url = runCatching { JSONObject(cuerpo).optString("url") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        return absoluto(url)
    }
}
