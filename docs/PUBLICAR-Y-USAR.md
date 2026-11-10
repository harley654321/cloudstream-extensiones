# Publicar y usar

## 1. Instalar el repositorio en el telefono

1. CloudStream 4.9.1 -> **Ajustes -> Extensiones**.
2. **Anadir repositorio** y pegar:

```
https://raw.githubusercontent.com/harley654321/cloudstream-extensiones/main/repo.json
```

3. Abre el repositorio y descarga la extension. La app comprueba el `fileHash`
   SHA-256 antes de cargarla.

## 2. Comprobar que la fabrica publico bien

```bash
curl -s https://raw.githubusercontent.com/harley654321/cloudstream-extensiones/main/compiladas/plugins.json | head -40
curl -sI https://raw.githubusercontent.com/harley654321/cloudstream-extensiones/main/compiladas/PlantillaProvider.cs3
```

El `fileHash` de `plugins.json` debe coincidir con:

```bash
curl -sL https://raw.githubusercontent.com/harley654321/cloudstream-extensiones/main/compiladas/PlantillaProvider.cs3 | sha256sum
```

## 3. Actualizar una extension

Edita su codigo, push a `main` y espera a que termine el workflow. La fabrica
sube el `version` y la app la marca como actualizable (o la actualiza sola, segun
la preferencia de autoactualizacion del usuario).

## 4. Instalar a mano un `.cs3`

```bash
adb push compiladas/MiProveedor.cs3 /sdcard/Download/
```
y desde la app, instalar extension desde fichero. En Android 11+ concede
"acceso a todos los archivos" a CloudStream.

## 5. Si algo falla

- Workflow en rojo: Actions -> Fabrica de extensiones -> run -> paso con error.
- Plugin cargado pero vacio: abre el log de la app (`Ajustes -> Debug`) y busca
  `PluginInstance`; las excepciones de `load()` se registran ahi.
- Cambios que no llegan: la app cachea `plugins.json`; fuerza actualizacion
  del repositorio desde Ajustes -> Extensiones.
