package com.foxcloud.seriesdonghua

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SeriesDonghuaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(SeriesDonghuaProvider())
    }
}
