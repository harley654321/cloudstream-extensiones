# Investigacion: fuentes oficiales y hallazgos (10 nov 2026)

## Fuentes revisadas

| Fuente | Que se saco |
|---|---|
| `recloudstream/cloudstream` (rama `master`, ultimo commit `5d66bbef`, 9 nov 2026) | Codigo real de la app 4.9.1: `PluginManager`, `RepositoryManager`, `BasePlugin`, `MainAPI`, `ExtractorApi`. |
| `recloudstream/csdocs` | Guias oficiales: crear proveedores, publicar repositorio JSON, scraping. |
| `recloudstream/TestPlugins` | Plantilla oficial de repositorio de plugins y su `build.yml`. |
| `recloudstream/extensions` | Repositorio oficial: estructura, `build.gradle.kts` y `repo.json` reales. |
| `recloudstream/gradle` | Plugin de Gradle: tareas `make`, `makePluginsJson`, `ensureJarCompatibility` y formato exacto de `plugins.json`. |
| `recloudstream/cs-repos` | Lista de repositorios de extensiones de la comunidad. |
| Extensiones de terceros activas | `CakesTwix/cloudstream-extensions-uk` (42 proveedores, nov 2026), `Luna712/Luna712-CloudStream-Extensions` (ago 2026), `phisher98/cloudstream-extensions-phisher`. |

## Hallazgos tecnicos verificados

1. **La app 4.9.1 no firma ni verifica firmas de plugins.** El `.cs3` es un zip
   con `manifest.json` + `classes.dex`; la integridad se comprueba con
   `fileHash` (SHA-256) del `plugins.json` del repositorio.
2. **`apiVersion` de los plugins vale 1** y hoy la app lo ignora (campo reservado).
3. **`internalName` manda**: es el nombre del proyecto/carpeta; `name` es solo la
   etiqueta visible. Cambiar `internalName` se interpreta como plugin nuevo.
4. **Los `.jar` existen pero no los usa Android** (`isCrossPlatform = true` en
   plugins de escritorio); la app movil solo consume `.cs3`. En esta fabrica
   `isCrossPlatform` queda desactivado.
5. **JitPack tiene artefacto estable de la libreria**: `4.9.1` (metadata de
   JitPack lo marca como `release`; `pre-release` tambien existe y es lo que usa
   la plantilla oficial). Fijamos `4.9.1` para que el plugin compile exactamente
   contra la API que trae la app estable del telefono.
6. **`org.gradle.jvmargs=-Xmx4g` y JDK 17** bastan: el build completo de una
   extension tarda ~1 minuto en 4 nucleos.
7. **El esquema clasico de publicacion** (rama `builds` + `git commit --amend
   --force`) borra historial. Esta fabrica publica en `compiladas/` sobre `main`.
8. **Los commits hechos con `GITHUB_TOKEN` no disparan workflows**, asi que la
   fabrica no se realimenta a si misma; ademas el commit lleva `[skip ci]`.

## Comprobado en el entorno

- Build local con JDK 17 + Android SDK 35 + Gradle 8.12: **BUILD SUCCESSFUL**,
  `.cs3` de 8.857 bytes con `manifest.json` + `classes.dex`, `plugins.json`
  generado y reescrito por la fabrica con hash y tamano correctos.
- El unico aviso del build es la deprecacion de las tareas `task<Delete>("clean")`
  del esquema clasico; no afecta al artefacto.
