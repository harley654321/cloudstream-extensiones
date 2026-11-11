package com.foxcloud.monoschinos

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MonoschinosPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MonoschinosProvider())
    }
}
