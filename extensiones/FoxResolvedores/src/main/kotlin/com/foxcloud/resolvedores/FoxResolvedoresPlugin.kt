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
        // OK.ru: la app busca una lista de mp4 que ya no existe; aqui va el HLS.
        registerExtractorAPI(OkRuFox())
        // Rumble: el reproductor lleva las pistas mp4 dentro del propio HTML.
        registerExtractorAPI(RumbleFox())
        // Filemoon es Byse por dentro (misma API y mismo sobre cifrado).
        registerExtractorAPI(ByseFox("https://filemoon.sx"))
        registerExtractorAPI(ByseFox("https://bysekoze.com"))
        registerExtractorAPI(ByseFox("https://bysezoxexe.com"))
        registerExtractorAPI(ByseFox("https://byselapuix.com"))
    }
}
