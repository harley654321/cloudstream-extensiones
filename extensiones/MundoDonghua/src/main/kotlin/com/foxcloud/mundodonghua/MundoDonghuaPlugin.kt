package com.foxcloud.mundodonghua

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MundoDonghuaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MundoDonghuaProvider())
    }
}
