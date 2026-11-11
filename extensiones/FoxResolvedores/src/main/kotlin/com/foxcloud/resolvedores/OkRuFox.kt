package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * OK.ru (el "OK.ru" de varias extensiones de donghua).
 *
 * El extractor de la app busca la lista "videos":[{"name","url"}] dentro de la
 * pagina del reproductor, pero OK.ru ya casi nunca la manda: ahora el video va
 * solo por HLS. Aqui se lee 'hlsManifestUrl' del JSON del reproductor (viene
 * escapado como &quot; y con \u0026 en la direccion) y se entrega el master
 * m3u8. Si de verdad viniera la lista de mp4, tambien se entrega.
 *
 * No hay que mandar Referer especial: ok.ru abre el embed desde cualquier sitio.
 */
class OkRuFox : ExtractorApi() {
    override val name = "Fox OK.ru"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    private val cabeceras = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "*/*"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val codigo = Regex("""/video(?:embed)?/(\d+)""").find(url)?.groupValues?.get(1) ?: return

        val crudo = runCatching {
            app.get("https://ok.ru/videoembed/$codigo", headers = cabeceras, referer = "https://ok.ru/").text
        }.getOrNull() ?: return

        // El JSON del reproductor viene dentro de un atributo HTML: se desescapa
        // igual que hace la app para poder leerlo como texto normal.
        val html = crudo
            .replace("&quot;", "\"")
            .replace("\\&quot;", "\"")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }

        val hls = Regex("""hlsManifestUrl"?\s*:\s*"?([^"'\\]+)""").find(html)?.groupValues?.get(1)
        if (hls != null && hls.contains(".m3u8")) {
            callback(
                newExtractorLink(name, "OK.ru", hls, ExtractorLinkType.M3U8) {
                    this.referer = "https://ok.ru/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        var alguno = false
        Regex(""""name":"([^"]+)","url":"([^"]+)"""").findAll(html).forEach { m ->
            val etiqueta = m.groupValues[1]
            val archivo = m.groupValues[2].let { if (it.startsWith("//")) "https:$it" else it }
            if (!archivo.startsWith("http")) return@forEach
            alguno = true
            callback(
                newExtractorLink(name, "OK.ru $etiqueta", archivo, ExtractorLinkType.VIDEO) {
                    this.referer = "https://ok.ru/"
                    this.quality = when {
                        etiqueta.contains("1080", true) -> Qualities.P1080.value
                        etiqueta.contains("720", true) -> Qualities.P720.value
                        etiqueta.contains("480", true) -> Qualities.P480.value
                        etiqueta.contains("360", true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                }
            )
        }
        if (!alguno) return
    }
}
