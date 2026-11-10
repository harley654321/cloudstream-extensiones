package com.foxcloud.plantilla

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Punto de entrada del plugin. CloudStream busca esta anotacion al cargar el .cs3
 * y ejecuta load(), donde se registran los proveedores y extractores.
 */
@CloudstreamPlugin
class PlantillaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PlantillaProvider())
    }
}
