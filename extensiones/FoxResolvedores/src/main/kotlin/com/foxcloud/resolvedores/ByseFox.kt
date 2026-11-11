package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Familia Byse (bysezoxexe.com, byselapuix.com). Su reproductor es una SPA:
 * primero se pide /api/videos/<codigo>/embed/details, que a veces trae
 * "sources" y otras solo "embed_frame_url"; se prueban las dos y, si no hay
 * suerte, se busca un archivo directo en el HTML.
 */
class ByseFox(private val dominio: String = "https://bysezoxexe.com") : ExtractorApi() {
    override val name = "Fox Byse"
    override val mainUrl = dominio
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

        val detalles = runCatching {
            app.get("https://$host/api/videos/$codigo/embed/details", referer = url).text
        }.getOrNull() ?: ""

        var archivo = Campos.valor(detalles, "file") ?: Campos.valor(detalles, "src")

        // Si la API no trae fuentes, se sigue el marco del reproductor.
        if (archivo == null) {
            val marco = Campos.valor(detalles, "embed_frame_url")
            if (marco != null) {
                val destino = if (marco.startsWith("http")) marco else "https://$host/" + marco.trimStart('/')
                val html = runCatching { app.get(destino, referer = url).text }.getOrNull() ?: ""
                archivo = Campos.archivo(html)
            }
        }

        if (archivo == null) {
            val html = runCatching { app.get(url, referer = "https://$host/").text }.getOrNull() ?: ""
            archivo = Campos.archivo(html)
        }

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
