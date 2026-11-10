rootProject.name = "cloudstream-extensiones"

// ---------------------------------------------------------------------------
// Deteccion automatica de extensiones.
// Cada carpeta dentro de `extensiones/` que tenga su propio build.gradle.kts se
// incluye como subproyecto de Gradle. No hay que tocar este fichero al anadir
// una extension nueva: basta con crear la carpeta.
// ---------------------------------------------------------------------------

val carpetaCodigo = File(rootDir, "extensiones")

// Extensiones desactivadas temporalmente (nombre exacto de la carpeta).
val desactivadas = listOf<String>()

if (carpetaCodigo.exists()) {
    carpetaCodigo.listFiles()
        ?.filter { it.isDirectory && File(it, "build.gradle.kts").exists() }
        ?.map { it.name }
        ?.sorted()
        ?.filterNot { desactivadas.contains(it) }
        ?.forEach { nombre ->
            include(":$nombre")
            project(":$nombre").projectDir = File(carpetaCodigo, nombre)
            println("Extension incluida: $nombre")
        }
}
