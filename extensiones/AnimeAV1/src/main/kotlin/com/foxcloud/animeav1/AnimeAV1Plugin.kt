package com.foxcloud.animeav1

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeAV1Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeAV1Provider())
    }
}
