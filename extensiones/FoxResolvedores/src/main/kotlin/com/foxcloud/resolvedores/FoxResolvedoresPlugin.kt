package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Extension de solo resolvedores: no anade ningun proveedor de peliculas,
 * registra reproductores en la lista global de la app para que los use
 * cualquier extension que llame a loadExtractor.
 */
@CloudstreamPlugin
class FoxResolvedoresPlugin : BasePlugin() {
    override fun load() {
        registerExtractorAPI(UqloadFox())
        registerExtractorAPI(ByseFox())
        registerExtractorAPI(VidaraFox())
    }
}
