// Version del plugin: la fabrica la incrementa sola cuando detecta cambios.
version = 1
cloudstream {
    description = "Plantilla de demostracion de la fabrica: contenido abierto de la Blender Foundation"
    authors = listOf("harley654321")

    /**
     * status: 0 = caida, 1 = ok, 2 = lenta, 3 = solo beta
     */
    status = 1
    tvTypes = listOf("Movie")
    language = "es"
    // requiresResources = true solo si la extension trae layouts, drawables, etc.
}

android {
    namespace = "com.foxcloud.plantilla"
}
