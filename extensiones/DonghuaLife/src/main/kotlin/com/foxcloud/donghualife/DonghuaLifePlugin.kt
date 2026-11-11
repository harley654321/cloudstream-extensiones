package com.foxcloud.donghualife

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DonghuaLifePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DonghuaLifeProvider())
    }
}
