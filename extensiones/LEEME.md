# `extensiones/` = codigo fuente

Una carpeta por extension. El nombre de la carpeta es el nombre interno del
plugin (no lo cambies despues de publicar) y debe contener:

```
MiProveedor/
├── build.gradle.kts          version + metadatos (descripcion, autores, tvTypes...)
└── src/main/
    ├── AndroidManifest.xml   siempre vacio: <manifest />
    └── kotlin/com/foxcloud/miproveedor/
        ├── MiProveedor.kt    la clase que hereda de MainAPI
        └── MiProveedorPlugin.kt  clase con @CloudstreamPlugin
```

Para crear una extension nueva copia `PlantillaProvider/` con otro nombre.
La deteccion es automatica: `settings.gradle.kts` incluye toda carpeta con
`build.gradle.kts`.
