package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Vidara (vidara.to). El reproductor pide la pista a POST /api/stream
 * con el codigo del archivo y responde un master HLS.
 */
class VidaraFox : ExtractorApi() {
    override val name = "Fox Vidara"
    override val mainUrl = "https://vidara.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val codigo = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (codigo.isBlank()) return
        val cuerpo = runCatching {
            app.post(
                "$mainUrl/api/stream",
                json = mapOf("filecode" to codigo, "device" to "web"),
                referer = url
            ).text
        }.getOrNull() ?: return
        val enlace = Campos.valor(cuerpo, "streaming_url")?.takeIf { it.startsWith("http") } ?: return
        callback(
            newExtractorLink(name, name, enlace, ExtractorLinkType.M3U8) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
