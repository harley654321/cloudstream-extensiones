# Arquitectura de la fabrica

## Piezas

| Pieza | Para que sirve |
|---|---|
| `settings.gradle.kts` | Descubre `extensiones/*/build.gradle.kts` y los incluye como subproyectos. Anadir carpeta = anadir extension. |
| `build.gradle.kts` | Configuracion comun: AGP 8.7.3, Kotlin 2.3.0, `minSdk 21`, `compileSdk 35`, `jvmTarget 1.8`, y los stubs de la API `4.9.1`. |
| `tools/cloudstream-gradle-plugin.jar` | Plugin de Gradle de CloudStream vendido en el repo: empaqueta el `.cs3` y genera `plugins.json` sin depender de JitPack. Ver `tools/LEEME.md`. |
| `extensiones/<Nombre>/` | Codigo de la extension y su `version` + metadatos. |
| `scripts/fabrica.py` | Versionado por hash de fuentes y publicacion de artefactos. |
| `.github/workflows/fabrica.yml` | Ejecuta la fabrica en cada push a `main`. |
| `compiladas/` | Salida publica: `.cs3`, `plugins.json`, `index.json`. |
| `repo.json` | Fichero que se pega en CloudStream para dar de alta el repositorio. |

## Que genera Gradle

El plugin `com.lagradost.cloudstream3.gradle` registra estas tareas:

- `make`: empaqueta el `.cs3` (zip con `manifest.json` + `classes.dex`, mas los
  recursos si `requiresResources = true`).
- `makePluginsJson`: agrega las entradas de cada extension en `build/plugins.json`.
- `ensureJarCompatibility`: valida el `.jar` de plugins multiplataforma
  (`isCrossPlatform = true`). Aqui no se usa: la app Android consume `.cs3`.
- `deployWithAdb`: instala el `.cs3` en un dispositivo conectado por ADB.
- `cleanCache` / `generateSources`: utilidades del stub.

Contenido real de un `.cs3` generado en esta fabrica:

```
manifest.json   {"requiresResources":false,"version":1,
                 "pluginClassName":"com.foxcloud.plantilla.PlantillaPlugin",
                 "name":"PlantillaProvider"}
classes.dex
```

## Contrato con la app

- `repo.json`: `name`, `description`, `iconUrl` (opcional), `manifestVersion: 1`,
  `pluginLists: [ ... ]`.
- `plugins.json`: lista de objetos con `url`, `name`, `internalName`, `version`,
  `status`, `apiVersion`, `authors`, `description`, `language`, `tvTypes`,
  `iconUrl`, `repositoryUrl`, `fileSize`, `fileHash`.
- La app compara `version` para decidir si actualiza y verifica `fileHash`
  (`sha256-<hex>`) al descargar.
- `internalName` es el nombre de la carpeta del proyecto: no lo cambies despues
  de publicar o la app lo tratara como un plugin nuevo.

## Versionado automatico

`scripts/fabrica.py preparar` calcula el SHA-256 de todos los ficheros de la
carpeta (excluyendo `build/` y `.gradle/`). Si cambia respecto al estado guardado
en `.factory/estado.json`, sube `version` en 1 dentro del `build.gradle.kts` de
esa extension. Asi cada push publica una version nueva solo de lo que cambio.

## Por que no hay rama `builds`

El esquema clasico de recloudstream publica en una rama `builds` con
`git commit --amend --force`. Aqui se publica en `main`, dentro de `compiladas/`:
queda historial real de cada compilacion, no se reescribe la historia y la URL
de los plugins es estable y cacheable por el CDN de `raw.githubusercontent.com`.
