package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Rumble (el "Rumble" de Series Donghua y de otras webs).
 *
 * La pagina del reproductor (rumble.com/embed/<codigo>/) ya trae dentro el JSON
 * con todas las pistas del video, aunque no se vea a simple vista:
 *
 *   p.f["v57anuh"]={"fps":25,...,"u":{"mp4":{"url":".../XPc_s.caa.mp4",...}},
 *                   "ua":{"mp4":{"360":{"url":".../XPc_s.baa.mp4","meta":{...}},
 *                               "480":{...},"720":{...},"240":{...}}}}
 *
 * Aqui se lee ese bloque y se entregan los mp4 con su calidad. Los ficheros
 * aceptan Range (206), asi que se puede buscar dentro del video sin problema.
 * No hace falta Resolvedor externo ni descifrado: es HTML normal.
 */
class RumbleFox : ExtractorApi() {
    override val name = "Fox Rumble"
    override val mainUrl = "https://rumble.com"
    override val requiresReferer = false

    private val cabeceras = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml",
        "Accept-Language" to "es-ES,es;q=0.9"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val codigo = Regex("""/(?:embed/)?(v[a-zA-Z0-9]{5,})""").find(url)?.groupValues?.get(1)
            ?: return

        val html = runCatching {
            app.get(
                "https://rumble.com/embed/$codigo/",
                headers = cabeceras,
                referer = "https://rumble.com/"
            ).text
        }.getOrNull()?.replace("\\/", "/") ?: return

        val emitidos = mutableSetOf<String>()

        // Pistas por calidad (las que trae "ua": 240, 360, 480, 720...).
        Regex(""""(240|360|480|720|1080|1440|2160)":\{"url":"(https://[^"]+?\.mp4)"""")
            .findAll(html)
            .forEach { m ->
                val archivo = m.groupValues[2]
                if (!emitidos.add(archivo)) return@forEach
                callback(
                    newExtractorLink(name, "Rumble ${m.groupValues[1]}p", archivo, ExtractorLinkType.VIDEO) {
                        this.referer = "https://rumble.com/"
                        this.quality = calidad(m.groupValues[1])
                    }
                )
            }

        // Pista "auto" (la que el propio reproductor usa por defecto).
        Regex(""""u":\{"mp4":\{"url":"(https://[^"]+?\.mp4)"""")
            .find(html)
            ?.groupValues?.get(1)
            ?.let { archivo ->
                if (emitidos.add(archivo)) {
                    callback(
                        newExtractorLink(name, "Rumble", archivo, ExtractorLinkType.VIDEO) {
                            this.referer = "https://rumble.com/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
    }

    private fun calidad(etiqueta: String): Int = when (etiqueta) {
        "2160" -> Qualities.P2160.value
        "1440" -> Qualities.P1440.value
        "1080" -> Qualities.P1080.value
        "720" -> Qualities.P720.value
        "480" -> Qualities.P480.value
        "360" -> Qualities.P360.value
        else -> Qualities.P240.value
    }
}
