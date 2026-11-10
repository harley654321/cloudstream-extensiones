# Crear una extension

Resumen del flujo oficial de recloudstream adaptado a esta fabrica.

## 1. Copiar la plantilla

```bash
cp -r extensiones/PlantillaProvider extensiones/MiProveedor
```

Cambia en `extensiones/MiProveedor/build.gradle.kts`:

```kotlin
android { namespace = "com.foxcloud.miproveedor" }
```

Renombra el paquete de los `.kt` a `com.foxcloud.miproveedor`, y las clases:
`MiProveedorPlugin` (la del `@CloudstreamPlugin`) y `MiProveedor` (la que hereda
de `MainAPI`). El nombre de la carpeta sera el `internalName` del plugin.

## 2. Metadatos

```kotlin
version = 1   // la fabrica la sube sola; no la toques a mano

cloudstream {
    description = "Que ofrece y de donde saca el contenido"
    authors = listOf("harley654321")
    status = 1                 // 0 caida, 1 ok, 2 lenta, 3 solo beta
    tvTypes = listOf("Movie")  // Movie, TvSeries, Anime, Live, Others...
    language = "es"
    requiresResources = false  // true solo si usas layouts/drawables
    // iconUrl = "https://...png"
}
```

## 3. Las cuatro funciones de un proveedor

Un `MainAPI` se define por cuatro cosas: buscar, portada, ficha y enlaces.

```kotlin
override suspend fun search(query: String, page: Int): SearchResponseList?
override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse
override suspend fun load(url: String): LoadResponse?
override suspend fun loadLinks(data: String, isCasting: Boolean,
                               subtitleCallback: (SubtitleFile) -> Unit,
                               callback: (ExtractorLink) -> Unit): Boolean
```

- `search`: devuelve `SearchResponse` (usa `newMovieSearchResponse`,
  `newTvSeriesSearchResponse`, `newAnimeSearchResponse`...).
- `getMainPage`: usa las secciones declaradas en
  `override val mainPage = listOf(MainPageData("Nombre", "dato", false))`.
- `load`: devuelve `newMovieLoadResponse`, `newTvSeriesLoadResponse`,
  `newLiveStreamLoadResponse`... con caratula, sinopsis, año, generos, reparto,
  recomendaciones y episodios.
- `loadLinks`: emite `ExtractorLink` con `callback(...)` y devuelve `true`.

## 4. Scraping

La app no expone un navegador: se trabaja con HTTP y parseo.

```kotlin
val doc = app.get("$mainUrl/buscar", params = mapOf("q" to query),
                  headers = mapOf("User-Agent" to "Mozilla/5.0")).document
val items = doc.select("div.resultado").mapNotNull { el ->
    val a = el.selectFirst("a") ?: return@mapNotNull null
    newMovieSearchResponse(a.text(), fixUrl(a.attr("href")), TvType.Movie) {
        posterUrl = el.selectFirst("img")?.attr("src")
    }
}
```

- `app.get/app.post` (NiceHttp) para HTML; `.document` da Jsoup.
- `.parsedSafe<T>()` / `tryParseJson<T>()` para APIs JSON.
- `fixUrl(...)` convierte rutas relativas en absolutas.
- Errores utiles: `throw ErrorLoadingException("sin titulo")` se muestra al usuario.

## 5. Enlaces de video

Dos caminos:

```kotlin
// a) La web usa un host conocido: deja que los extractores de la app lo resuelvan
loadExtractor(url, referer = mainUrl, subtitleCallback, callback)

// b) Enlace directo (mp4, m3u8, dash)
callback(newExtractorLink(source = name, name = name, url = directo,
                          type = ExtractorLinkType.VIDEO) {
    quality = Qualities.Unknown.value
})
```

Si el host es propio y ofuscado, se escribe un `ExtractorApi` y se registra en el
plugin con `registerExtractorAPI(MiExtractor())`.

## 6. Probar

```bash
./gradlew :MiProveedor:make           # genera el .cs3
./gradlew :MiProveedor:deployWithAdb  # lo instala en el movil por ADB
```

En Android 11+ hay que dar "acceso a todos los archivos" a CloudStream para que
pueda leer los plugins de la carpeta de pruebas.

## 7. Publicar

Push a `main`. La fabrica versiona, compila, publica y actualiza `plugins.json`.
