package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Uqload en los dominios nuevos (uqload.vc y compania).
 * La pagina del embed lleva un packer de javascript con el archivo real;
 * se descomprime y se toma la pista HLS o el mp4.
 */
class UqloadFox : ExtractorApi() {
    override val name = "Fox Uqload"
    override val mainUrl = "https://uqload.vc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val respuesta = app.get(url, referer = referer ?: mainUrl)
        val codigo = runCatching { getAndUnpack(respuesta.text) }.getOrNull() ?: respuesta.text
        val archivo = Campos.archivo(codigo) ?: return
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
