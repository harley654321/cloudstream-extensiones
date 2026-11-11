package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Mp4upload: CloudStream no lo trae. El embed es un videojs con el mp4 directo
 * en "src" (sirve por el puerto 183 del CDN). Exige Referer del sitio.
 */
class Mp4UploadFox : ExtractorApi() {
    override val name = "Fox Mp4Upload"
    override val mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pagina = runCatching { app.get(url, referer = referer ?: mainUrl).text }.getOrNull() ?: return
        val candidatos = Regex("""(?:src|file)\s*:\s*["']([^"']+)["']""").findAll(pagina).map { it.groupValues[1] }.toList()
        var enlace = candidatos.firstOrNull { it.contains(".mp4") || it.contains(".m3u8") }
            ?: Campos.archivo(pagina)
            ?: return
        enlace = enlace.trim()
        if (enlace.startsWith("//")) enlace = "https:" + enlace
        if (!enlace.startsWith("http")) return

        callback(
            newExtractorLink(
                name,
                name,
                enlace,
                if (enlace.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.mp4upload.com/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
