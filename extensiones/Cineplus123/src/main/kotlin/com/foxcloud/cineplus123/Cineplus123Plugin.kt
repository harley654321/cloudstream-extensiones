package com.foxcloud.cineplus123

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Cineplus123Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Cineplus123Provider())
    }
}
