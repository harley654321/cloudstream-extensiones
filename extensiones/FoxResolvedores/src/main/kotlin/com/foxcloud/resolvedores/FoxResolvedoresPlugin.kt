package com.foxcloud.resolvedores

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Extension de solo resolvedores: no tiene catalogo propio.
 * Registra en la lista global de la app los reproductores que CloudStream no
 * trae de serie, para que los use cualquier extension que llame a loadExtractor.
 * Lo que CloudStream ya resuelve (por ejemplo vidara.to) se deja en manos de la
 * app, para que sus actualizaciones sigan entrando solas.
 */
@CloudstreamPlugin
class FoxResolvedoresPlugin : BasePlugin() {
    override fun load() {
        registerExtractorAPI(UqloadFox())
        registerExtractorAPI(ByseFox())
    }
}
