# `tools/cloudstream-gradle-plugin.jar`

Plugin de Gradle de CloudStream (`com.lagradost.cloudstream3.gradle`), version
`master` del 2 de julio de 2026, empaquetado como jar autonomo (incluye ASM y
jadb). La fabrica lo carga desde aqui:

```kotlin
classpath(files("tools/cloudstream-gradle-plugin.jar"))
```

Motivo: el repositorio oficial lo publica solo en JitPack como `-SNAPSHOT`, y
JitPack responde 404 de forma intermitente a los runners de GitHub Actions, lo
que rompia compilaciones validas. Con el jar dentro del repo la compilacion es
reproducible y no depende de terceros. Solo aporta las tareas de empaquetado
`.cs3` / `plugins.json`.

## Regenerarlo (cuando CloudStream cambie su formato de plugin)

```bash
git clone https://github.com/recloudstream/gradle
cd gradle
cat >> build.gradle.kts <<'GRADLE'
tasks.register<Jar>("fatJar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("cloudstream-gradle-plugin.jar")
    destinationDirectory.set(layout.buildDirectory.dir("fat"))
    from(sourceSets.main.get().output)
    val necesarias = listOf("asm", "asm-tree", "asm-commons", "jadb", "kotlin-stdlib")
    from({
        configurations.runtimeClasspath.get()
            .filter { f -> necesarias.any { f.name.startsWith(it) } }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
                "META-INF/MANIFEST.MF", "META-INF/versions/**", "module-info.class")
    }
}
GRADLE
./gradlew fatJar
cp build/fat/cloudstream-gradle-plugin.jar <repo>/tools/
```

Licencia del plugin original: la del proyecto CloudStream (GPL-3.0).
