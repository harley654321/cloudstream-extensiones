package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Byse (bysezoxexe.com). El reproductor es una SPA que publica sus fuentes
 * en /api/videos/<codigo>/embed/details. Si esa API no contesta, se mira el
 * HTML del embed por si trae el archivo directo.
 */
class ByseFox : ExtractorApi() {
    override val name = "Fox Byse"
    override val mainUrl = "https://bysezoxexe.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = url.substringAfter("://").substringBefore('/')
        val codigo = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (host.isBlank() || codigo.isBlank()) return

        val archivo = runCatching {
            val json = app.get("https://$host/api/videos/$codigo/embed/details", referer = url).text
            Campos.valor(json, "file") ?: Campos.valor(json, "src")
        }.getOrNull() ?: runCatching {
            Campos.archivo(app.get(url, referer = referer ?: "https://$host/").text)
        }.getOrNull()

        if (archivo == null || !archivo.startsWith("http")) return

        callback(
            newExtractorLink(
                name,
                name,
                archivo,
                if (archivo.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
