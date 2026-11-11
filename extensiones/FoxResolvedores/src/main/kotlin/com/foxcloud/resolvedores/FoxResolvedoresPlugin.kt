package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Extension de solo resolvedores: no tiene catalogo propio.
 * Registra los reproductores que la app NO trae de serie, para que los use
 * cualquier extension que llame a loadExtractor. Lo que CloudStream ya resuelve
 * (vidara.to, voe.sx, streamwish, vidoza...) se deja en manos de la app.
 */
@CloudstreamPlugin
class FoxResolvedoresPlugin : BasePlugin() {
    override fun load() {
        registerExtractorAPI(UqloadFox())
        registerExtractorAPI(Mp4UploadFox())
        registerExtractorAPI(ByseFox("https://bysezoxexe.com"))
        registerExtractorAPI(ByseFox("https://byselapuix.com"))
    }
}
