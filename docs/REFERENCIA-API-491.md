# Referencia: API de CloudStream 4.9.1

Extraido del codigo de la propia app y de la libreria en la etiqueta `4.9.1`
(9 de noviembre de 2026), que es la version contra la que compila esta fabrica.

## Plugin

```kotlin
@CloudstreamPlugin
class MiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MiProveedor())       // MainAPI
        registerExtractorAPI(MiExtractor())  // ExtractorApi (opcional)
    }
    override fun beforeUnload() { }          // opcional
}
```

`BasePlugin` expone ademas `filename` (ruta del `.cs3` cargado) y la clase
`BasePlugin.Manifest` (`name`, `pluginClassName`, `requiresResources`, `version`).

## MainAPI: propiedades que se sobrescriben

| Miembro | Tipo | Notas |
|---|---|---|
| `name` | `String` | Nombre visible del proveedor |
| `mainUrl` | `String` | Dominio base |
| `lang` | `String` | Código ISO, p. ej. `"es"` |
| `supportedTypes` | `Set<TvType>` | `Movie`, `TvSeries`, `Anime`, `Live`, `AsianDrama`, `Others`... |
| `hasMainPage` | `Boolean` | Activa la portada |
| `hasQuickSearch` | `Boolean` | Búsqueda rápida desde el buscador global |
| `mainPage` | `List<MainPageData>` | `MainPageData(name, data, horizontalImages)` |
| `providerType` | `ProviderType` | `DirectProvider` por defecto |
| `hasDownloadSupport` | `Boolean` | `true` por defecto |

## Funciones

```kotlin
open suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse
open suspend fun search(query: String, page: Int): SearchResponseList?
open suspend fun search(query: String): List<SearchResponse>?      // atajo sin paginar
open suspend fun load(url: String): LoadResponse?
open suspend fun loadLinks(data: String, isCasting: Boolean,
                           subtitleCallback: (SubtitleFile) -> Unit,
                           callback: (ExtractorLink) -> Unit): Boolean
```

## Constructores habituales

```kotlin
newHomePageResponse(request, lista)               // o (nombre, lista, hasNext)
newHomePageResponse(listOf(HomePageList("A", lista)))
newSearchResponseList(lista, hasNext = false)     // o lista.toNewSearchResponseList()
newMovieSearchResponse(titulo, url, TvType.Movie) { posterUrl = ...; year = ... }
newMovieLoadResponse(titulo, url, TvType.Movie, dataUrl) { plot = ...; tags = ... }
newTvSeriesLoadResponse(...) { episodes = listOf(newEpisode(dataUrl) { season = 1; episode = 1 }) }
newLiveStreamLoadResponse(...)
newExtractorLink(source, name, url, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value }
```

`ExtractorLinkType`: `VIDEO` (fichero continuo), `M3U8` (HLS), `DASH`, `TORRENT`.

## Utilidades

- `app` (NiceHttp): `app.get(url, headers, params)`, `app.post(...)`, `.text`, `.document` (Jsoup).
- `app.get(...).parsedSafe<T>()` / `AppUtils.tryParseJson<T>(text)` para JSON.
- `fixUrl(...)`, `fixUrlNull(...)`, `toRatingInt()`, `newEpisode(dataUrl) { }`.
- `loadExtractor(url, referer, subtitleCallback, callback)`: delega en los
  extractores de la app y de los plugins instalados.
- `errorLoadingException` / `ErrorLoadingException("mensaje")` para fallos legibles.

## Dependencias disponibles en el classpath de la app

`kotlin-stdlib`, NiceHttp 0.4.11, Jsoup 1.18.3, Jackson 2.13.1. Se pueden anadir
mas (`androidx.recyclerview`, `material`, OkHttp...) declarandolas en el
`build.gradle.kts` de la extension; las que ya lleva la app no engordan el `.cs3`.

## Reglas que rompen la carga

1. `jvmTarget` debe ser **1.8** y hace falta `-Xno-call-assertions
   -Xno-param-assertions -Xno-receiver-assertions`.
2. No subir Jackson de **2.13.1** (incompatible con Android antiguo).
3. `minSdk 21`; `compileSdk 35`.
4. Compilar contra `com.github.recloudstream.cloudstream:library:4.9.1` (esta
   fabrica lo fija). Compilar contra `-SNAPSHOT` puede meter APIs que la app
   estable aun no tiene y el plugin fallara al cargar.
5. Una sola clase anotada con `@CloudstreamPlugin` por plugin.
6. `AndroidManifest.xml` vacio: `<manifest />`.

## Ficheros que genera y consume la app

`.cs3` (zip):

```
manifest.json    pluginClassName, name, version, requiresResources
classes.dex      codigo compilado
(res/...)        solo si requiresResources = true
```

`plugins.json` (una entrada por plugin):

```json
{
  "url": "https://.../compiladas/MiProveedor.cs3",
  "name": "MiProveedor",
  "internalName": "MiProveedor",
  "version": 3,
  "status": 1,
  "apiVersion": 1,
  "authors": ["harley654321"],
  "description": "...",
  "language": "es",
  "tvTypes": ["Movie"],
  "repositoryUrl": "https://github.com/harley654321/cloudstream-extensiones",
  "fileSize": 8857,
  "fileHash": "sha256-..."
}
```

`repo.json`:

```json
{ "name": "...", "description": "...", "manifestVersion": 1,
  "pluginLists": ["https://.../compiladas/plugins.json"] }
```
