# FoxCloud Extensiones

Fabrica propia de extensiones para **CloudStream 4.9.1** (estable, noviembre 2026).
Aqui vive el codigo de cada extension y, en `compiladas/`, los `.cs3` ya listos
para instalar. Todo se compila y se publica solo con GitHub Actions.

## Anadir el repositorio a CloudStream

En CloudStream: **Ajustes -> Extensiones -> Anadir repositorio** y pega:

```
https://raw.githubusercontent.com/harley654321/cloudstream-extensiones/main/repo.json
```

Ese `repo.json` apunta a `compiladas/plugins.json`, que es la lista que lee la app.

## Como funciona

```
extensiones/            codigo fuente: una carpeta por extension
  PlantillaProvider/      (nombre de carpeta = nombre interno del plugin)
  AnimeAV1/               extension de animeav1.com (ver docs/ANIMEAV1.md)
  Cinecalidad/            extension de cinecalidad.am (ver docs/CINECALIDAD.md)
compiladas/             lo que publica la fabrica (no se toca a mano)
  PlantillaProvider-v3.cs3  plugin compilado (la version va en el nombre: cada
  AnimeAV1-v7.cs3           publicacion estrena URL y el CDN no sirve cache vieja)
  Cinecalidad-v2.cs3
  plugins.json            indice que lee CloudStream
  index.json              resumen legible de la ultima compilacion
scripts/fabrica.py      versiona y publica
.github/workflows/      la fabrica (compila en cada push a main)
```

Ciclo completo, sin intervencion:

1. Editas el codigo de una extension y haces push a `main`.
2. Actions detecta que extensiones cambiaron (hash de sus fuentes) y les sube
   el `version` en 1.
3. Compila todas con `./gradlew make makePluginsJson`.
4. Copia los `.cs3` a `compiladas/`, recalcula tamano y SHA-256 y regenera
   `plugins.json` e `index.json`.
5. Hace commit y push. CloudStream ve `version` nueva y actualiza la extension.

## Anadir una extension nueva

Copia la carpeta `extensiones/PlantillaProvider/` con otro nombre, cambia el
`namespace` de su `build.gradle.kts`, renombra paquete y clases, y haz push.
No hay que registrar nada: `settings.gradle.kts` descubre las carpetas solas.
Detalles en [docs/CREAR-EXTENSION.md](docs/CREAR-EXTENSION.md).

## Compilar en local

Necesitas JDK 17 y el Android SDK (platform 35 + build-tools 35.0.0).

```bash
python3 scripts/fabrica.py preparar
./gradlew make makePluginsJson
python3 scripts/fabrica.py publicar
```

## Documentacion

- [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md): como esta montada la fabrica.
- [docs/CREAR-EXTENSION.md](docs/CREAR-EXTENSION.md): escribir un proveedor paso a paso.
- [docs/REFERENCIA-API-491.md](docs/REFERENCIA-API-491.md): API de CloudStream 4.9.1.
- [docs/PUBLICAR-Y-USAR.md](docs/PUBLICAR-Y-USAR.md): instalar y verificar en el telefono.
- [docs/INVESTIGACION.md](docs/INVESTIGACION.md): fuentes oficiales y hallazgos verificados.

## Aviso

Los plugins solo declaran proveedores. El uso que se haga del contenido de cada
sitio es responsabilidad de quien lo usa; trabaja con fuentes propias, APIs
publicas o contenido con licencia que permita su distribucion.
